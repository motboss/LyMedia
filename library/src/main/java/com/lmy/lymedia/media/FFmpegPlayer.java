package com.lmy.lymedia.media;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.util.Log;
import android.view.SurfaceHolder;

import com.lmy.lymedia.media.render.Filter;

import org.bytedeco.javacv.AndroidFrameConverter;
import org.bytedeco.javacv.FFmpegFrameFilter;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.OpenCVFrameConverter;

import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.util.LinkedList;
import java.util.Queue;

import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_RGB565;
import static org.bytedeco.javacpp.avutil.AV_PIX_FMT_RGBA;

/**
 * Created by Administrator on 2016/3/23.
 */
public class FFmpegPlayer extends Player {
    private final static String TAG = "FFmpegPlayer";
    private SurfaceHolder mHolder;
    private FFmpegFrameGrabber mFrameGrabber;//解码器
    private FFmpegFrameGrabber mAudioGrabber;//解码器
    private AndroidFrameConverter mFrameConverter;
    private Frame cacheFrame;//缓存帧
    private PlayerThread mPlayerThread;
    private AudioThread audioThread;

    //状态相关
    private boolean hasInit = false;

    //设置相关
    private String sourcePath;
    private Filter mFilter;

    public static FFmpegPlayer create(SurfaceHolder mHolder) {
        return new FFmpegPlayer(mHolder);
    }

    public static FFmpegPlayer create(SurfaceHolder mHolder, String path) {
        return new FFmpegPlayer(mHolder, path);
    }

    public FFmpegPlayer(SurfaceHolder mHolder) {
        this.mHolder = mHolder;
    }

    public FFmpegPlayer(SurfaceHolder mHolder, String path) {
        this.mHolder = mHolder;
        setDataSource(path);
    }

    public void setDataSource(String path) {
        this.sourcePath = path;
        this.curFrameNumber = 0;
        this.hasInit = false;
        clearView();
        mFrameConverter = new AndroidFrameConverter();
        try {
            //如果已经有实例，则先释放资源再初始化
            if (mFrameGrabber != null) {
                mFrameGrabber.stop();
                mFrameGrabber.release();
                mFrameGrabber = null;
            }
            if (mAudioGrabber != null) {
                mAudioGrabber.stop();
                mAudioGrabber.release();
                mAudioGrabber = null;
            }
            mFrameGrabber = FFmpegFrameGrabber.createDefault(path);
            mFrameGrabber.setPixelFormat(AV_PIX_FMT_RGBA);
            mAudioGrabber = FFmpegFrameGrabber.createDefault(path);
            mFrameGrabber.start();
            this.rate = Math.round(1000d / mFrameGrabber.getFrameRate());
            Log.v("000", String.format("width=%d, height=%d, delay=%d, frame lenght=%d", mFrameGrabber.getImageWidth(), mFrameGrabber.getImageHeight(), rate, mFrameGrabber.getLengthInFrames()));
            Log.v("init", "AudioChannels=" + mFrameGrabber.getAudioChannels() + ", AudioBitrate=" + mFrameGrabber.getSampleRate());
            mAudioGrabber.start();
        } catch (FrameGrabber.Exception e) {
            e.printStackTrace();
        }
        mPlayerThread = new PlayerThread();
        audioThread = new AudioThread();
        audioThread.initTrack(mFrameGrabber.getSampleRate(), mFrameGrabber.getAudioChannels());
        mPlayerThread.start();
        this.hasInit = true;
        if (isAutoPlay()) play();
    }

    private int frameType(Frame frame) {
        if (frame == null) return -1;
        if (frame.image != null) return 0;
        else if (frame.samples != null) return 1;
        else return -1;
    }

    private class AudioThread extends BaseThread {
        private AudioDevice audioDevice;
        private Queue<Buffer> buffers;

        public AudioThread() {
            buffers = new LinkedList<>();
        }

        public void initTrack(int sampleRate, int channels) {
            audioDevice = new AudioDevice(sampleRate, channels);
        }

        public AudioDevice getAudioDevice() {
            return audioDevice;
        }

        @Override
        public void run() {
            super.run();
            sleepFunc(rate);
            while (run) {
                if (!play) {//|| size() <= 1
                    sleepFunc(rate);
                    continue;
                }
                synchronized (mAudioGrabber) {
                    if (mAudioGrabber.getFrameNumber() >= curFrameNumber + 1) {
                        sleepFunc(rate);
                        continue;
                    }
                    try {
                        if (!play) continue;
                        Frame f = mAudioGrabber.grabSamples();
                        if (frameType(f) == 1) {
                            audioDevice.writeSamples(f.samples);
                        } else {
                            sleepFunc(5);
                        }
                    } catch (FrameGrabber.Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        public int size() {
            synchronized (this.buffers) {
                return this.buffers.size();
            }
        }

        public Buffer poll() {
            synchronized (this.buffers) {
                return this.buffers.poll();
            }
        }

        public void offer(Buffer buffer) {
            synchronized (this.buffers) {
                this.buffers.offer(buffer);
            }
        }

        public void write(Buffer[] buffers) {
            synchronized (this.buffers) {
                for (int i = 0; i < buffers.length; i++) {
                    FloatBuffer fb = (FloatBuffer) buffers[0];
                    fb.rewind();
                    float[] data = new float[fb.capacity()];
                    fb.get(data);
                    offer(FloatBuffer.wrap(data));
                }
            }
        }

        @Override
        public void stopRun() {
            super.stopRun();
            audioDevice.release();
        }
    }

    private void clearView() {
        synchronized (mHolder) {
            Canvas canvas = mHolder.lockCanvas();
            if (canvas == null) return;
            canvas.drawColor(Color.BLACK);
            mHolder.unlockCanvasAndPost(canvas);
        }
    }


    private Frame filter(Frame frame) {
        if (mFilter != null && mFilter.isStarting())
            return mFilter.filter(frame);
        return frame;
    }

    private boolean draw(Frame frame) {
        if (frame == null || frame.image == null) {
            return false;
        }
//        try {
//            mFilter.push(frame);
//            while ((frame = mFilter.pull()) != null) {
//
//            }
//        } catch (FrameFilter.Exception e) {
//            e.printStackTrace();
//        }
        frame = filter(frame);
        Bitmap bmp = mFrameConverter.convert(frame);
        if (bmp == null) return false;
        synchronized (mHolder) {
            Canvas canvas = mHolder.lockCanvas();
            if (canvas == null) return true;
            canvas.drawBitmap(bmp, null, new Rect(0, 0, canvas.getWidth(), frame.imageHeight * canvas.getWidth() / frame.imageWidth), null);
            mHolder.unlockCanvasAndPost(canvas);
        }
        return true;
    }

    private class PlayerThread extends BaseThread {

        @Override
        public void run() {
            super.run();
            try {
                seek(5);
                audioThread.start();
                synchronized (mFrameGrabber) {
                    while (run && curFrameNumber < mFrameGrabber.getLengthInFrames() - 5) {
                        if (!play) {
                            sleepFunc(rate);
                            continue;
                        }
                        long time = System.currentTimeMillis();
                        if (!play) continue;
                        cacheFrame = mFrameGrabber.grabImage();
                        long time2 = System.currentTimeMillis();
                        if (draw(cacheFrame)) {//frameType(cacheFrame) == 0
                            ++curFrameNumber;
                            if (isLooping() && curFrameNumber >= mFrameGrabber.getLengthInFrames() - 5) {//重复播放
                                Log.w(TAG, "rePlay!!!");
                                seek(0);
                                continue;
                            }
                            long wait = rate - System.currentTimeMillis() + time;
//                        Log.v(TAG, "grabber time=" + (time2 - time) + ", draw time=" + (rate - wait - time2 + time) + ", wait=" + wait);
//                        if (wait < 0)
//                            Log.w(TAG, "wait=" + wait + ", Rendering time is low!");
                            sleepFunc(wait < 0 ? 0 : wait);
                        } else {//强制跳帧播放
                            ++curFrameNumber;
                            seek(curFrameNumber);
                        }
                    }
//                    else if (frameType(cacheFrame) == 1) {
//                        audioThread.getAudioDevice().writeSamples(cacheFrame.samples);
//                        audioThread.write(cacheFrame.samples);
//                    }
                }
            } catch (FrameGrabber.Exception e) {
                e.printStackTrace();
            }
        }
    }

    private class BaseThread extends Thread {
        protected boolean run = false;

        protected void sleepFunc(long time) {
            try {
                Thread.sleep(time);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            super.run();
            run = true;
        }

        public boolean isRun() {
            return run;
        }

        public void stopRun() {
            this.run = false;
        }
    }

    @Override
    public void seek(int number) {
        synchronized (mFrameGrabber) {
            if (number > mFrameGrabber.getLengthInFrames()) return;
            super.seek(number);
            this.curFrameNumber = number;
            try {
                mFrameGrabber.setFrameNumber(curFrameNumber);
                synchronized (mAudioGrabber) {
                    mAudioGrabber.setFrameNumber(curFrameNumber);
                }
            } catch (FrameGrabber.Exception e) {
                e.printStackTrace();
            }
        }

    }

    @Override
    public void play() {
        if (!hasInit) return;
        super.play();
    }

    @Override
    public void pause() {
        if (!hasInit) return;
        super.pause();
    }

    @Override
    public void stop() {
        if (!hasInit) return;
        super.stop();
        mPlayerThread.stopRun();
        audioThread.stopRun();
        if (mFilter != null) {
            mFilter.onStop();
            mFilter = null;
        }
        try {
            synchronized (mFrameGrabber) {
                if (mFrameGrabber != null) {
                    mFrameGrabber.stop();
                    mFrameGrabber.release();
                    mFrameGrabber = null;
                }
            }
            synchronized (mAudioGrabber) {
                if (mAudioGrabber != null) {
                    mAudioGrabber.stop();
                    mAudioGrabber.release();
                    mAudioGrabber = null;
                }
            }
        } catch (FrameGrabber.Exception e) {
            e.printStackTrace();
        }
    }

    public void setFilter(Filter filter) {
        this.mFilter = filter;
        mFilter.onCreate(getWidth(), getHeight());
    }

    @Override
    public int getWidth() {
        return mFrameGrabber.getImageWidth();
    }

    @Override
    public int getHeight() {
        return mFrameGrabber.getImageHeight();
    }

    @Override
    public boolean isLooping() {
        return looping;
    }

    @Override
    public void setLooping(boolean looping) {
        this.looping = looping;
    }

    @Override
    public boolean isAutoPlay() {
        return autoPlay;
    }

    @Override
    public void setAutoPlay(boolean autoPlay) {
        this.autoPlay = autoPlay;
    }

    public String getSourcePath() {
        return sourcePath;
    }
}
