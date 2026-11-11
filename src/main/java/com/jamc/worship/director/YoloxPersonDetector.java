package com.jamc.worship.director;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Local person detection with YOLOX-nano (Megvii, Apache-2.0), bundled in {@code /models/yolox_nano.onnx}, on ONNX
 * Runtime's CPU engine. No internet is used. Only COCO class 0 (person) is kept.
 *
 * <p>The model takes a 416×416 BGR picture with values 0..255 (the camera frame letterboxed into the top-left
 * corner, grey padding) and returns 3549 candidates (strides 8, 16 and 32) whose box is relative to its grid cell.
 */
public final class YoloxPersonDetector implements PersonDetector {

    private static final Logger log = LoggerFactory.getLogger(YoloxPersonDetector.class);
    public static final String RESOURCE = "/models/yolox_nano.onnx";
    static final int SIZE = 416;
    private static final int[] STRIDES = {8, 16, 32};
    private static final float NMS_IOU = 0.45f;
    /** Candidates below this are dropped before NMS; the tracker applies the operator's own threshold. */
    private static final float MIN_SCORE = 0.20f;

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final float[] input = new float[3 * SIZE * SIZE];
    private final int[][] grid; // per candidate: x cell, y cell, stride

    private YoloxPersonDetector(OrtEnvironment env, OrtSession session) {
        this.env = env;
        this.session = session;
        this.inputName = session.getInputNames().iterator().next();
        List<int[]> cells = new ArrayList<>();
        for (int s : STRIDES) {
            int n = SIZE / s;
            for (int y = 0; y < n; y++) {
                for (int x = 0; x < n; x++) {
                    cells.add(new int[]{x, y, s});
                }
            }
        }
        grid = cells.toArray(new int[0][]);
    }

    /**
     * Loads the bundled model. {@code threads} limits the CPU cores the model may use, so the video and audio keep
     * theirs.
     */
    public static YoloxPersonDetector load(int threads) throws OrtException, IOException {
        byte[] model;
        try (InputStream in = YoloxPersonDetector.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("The AI model " + RESOURCE + " is missing from the program files.");
            }
            model = in.readAllBytes();
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        opts.setIntraOpNumThreads(Math.max(1, threads));
        opts.setInterOpNumThreads(1);
        OrtSession session = env.createSession(model, opts);
        log.info("Auto Director AI model loaded ({} threads, CPU)", threads);
        return new YoloxPersonDetector(env, session);
    }

    @Override
    public String describe() {
        return "YOLOX-nano · CPU";
    }

    @Override
    public Prepared prepare(BufferedImage frame, Prepared reuse) {
        BufferedImage img = reuse != null && reuse.image() != null ? reuse.image()
                : new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_3BYTE_BGR);
        if (frame == null) {
            return new Prepared(img, 1, 0, 0);
        }
        double r = Math.min((double) SIZE / frame.getWidth(), (double) SIZE / frame.getHeight());
        int w = Math.max(1, (int) Math.round(frame.getWidth() * r));
        int h = Math.max(1, (int) Math.round(frame.getHeight() * r));
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(114, 114, 114));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(frame, 0, 0, w, h, null);
        g.dispose();
        return new Prepared(img, r, frame.getWidth(), frame.getHeight());
    }

    @Override
    public synchronized List<Detection> detect(Prepared p) throws OrtException {
        if (p == null || p.image() == null || p.frameWidth() <= 0) {
            return List.of();
        }
        byte[] bgr = ((DataBufferByte) p.image().getRaster().getDataBuffer()).getData();
        int plane = SIZE * SIZE;
        for (int i = 0, j = 0; i < plane; i++, j += 3) {
            input[i] = bgr[j] & 0xFF;              // B
            input[plane + i] = bgr[j + 1] & 0xFF;  // G
            input[2 * plane + i] = bgr[j + 2] & 0xFF; // R
        }
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), new long[]{1, 3, SIZE, SIZE});
             OrtSession.Result result = session.run(Map.of(inputName, tensor))) {
            float[][][] out = (float[][][]) result.get(0).getValue();
            return decode(out[0], p);
        }
    }

    private List<Detection> decode(float[][] rows, Prepared p) {
        List<float[]> found = new ArrayList<>();
        int n = Math.min(rows.length, grid.length);
        for (int i = 0; i < n; i++) {
            float[] r = rows[i];
            float score = r[4] * r[5]; // objectness × person
            if (score < MIN_SCORE) {
                continue;
            }
            int[] cell = grid[i];
            float cx = (r[0] + cell[0]) * cell[2];
            float cy = (r[1] + cell[1]) * cell[2];
            float w = (float) Math.exp(r[2]) * cell[2];
            float h = (float) Math.exp(r[3]) * cell[2];
            found.add(new float[]{cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2, score});
        }
        found.sort(Comparator.comparingDouble((float[] a) -> a[4]).reversed());
        List<Detection> kept = new ArrayList<>();
        List<float[]> keptRaw = new ArrayList<>();
        double fw = p.frameWidth() * p.scale();
        double fh = p.frameHeight() * p.scale();
        for (float[] c : found) {
            boolean overlap = false;
            for (float[] k : keptRaw) {
                if (iou(c, k) > NMS_IOU) {
                    overlap = true;
                    break;
                }
            }
            if (overlap) {
                continue;
            }
            keptRaw.add(c);
            Box b = Box.ofCorners(c[0] / fw, c[1] / fh, c[2] / fw, c[3] / fh).clamp();
            if (b.w() > 0.005 && b.h() > 0.01) {
                kept.add(new Detection(b, c[4]));
            }
        }
        return kept;
    }

    private static float iou(float[] a, float[] b) {
        float ix = Math.max(0, Math.min(a[2], b[2]) - Math.max(a[0], b[0]));
        float iy = Math.max(0, Math.min(a[3], b[3]) - Math.max(a[1], b[1]));
        float inter = ix * iy;
        float union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter;
        return union <= 0 ? 0 : inter / union;
    }

    @Override
    public synchronized void close() {
        try {
            session.close();
        } catch (OrtException e) {
            log.debug("Closing the AI model", e);
        }
    }
}
