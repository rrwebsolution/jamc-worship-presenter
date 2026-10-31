package com.jamc.worship.media;

/** Common controls for anything that plays over time (video, audio), used by the transport UI. */
public interface Transport {

    void playOrResume();

    void pause();

    void stop();

    /** Seeks to a position between 0 and 1. */
    void seek(float position);

    /** Volume 0..200 (100 = normal). */
    void setVolume(int volume);

    int volume();

    void setMuted(boolean muted);

    boolean isMuted();

    void setLoop(boolean loop);

    boolean isLoop();

    boolean isPlaying();

    long time();

    long length();

    boolean hasMedia();

    /** Listener is called on the event dispatch thread when playback state changes. */
    void addStateListener(Runnable listener);
}
