package codes.rik.kinesisvideo.gstreamer;

import static com.amazonaws.kinesisvideo.client.mediasource.MediaSourceState.STOPPED;
import static com.amazonaws.kinesisvideo.producer.StreamInfo.NalAdaptationFlags.NAL_ADAPTATION_FLAG_NONE;
import static com.amazonaws.kinesisvideo.producer.StreamInfo.StreamingType.STREAMING_TYPE_REALTIME;
import static java.util.Objects.requireNonNull;
import static org.freedesktop.gstreamer.BufferFlag.DELTA_UNIT;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

import javax.annotation.Nonnull;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.annotation.concurrent.NotThreadSafe;

import org.freedesktop.gstreamer.Buffer;
import org.freedesktop.gstreamer.Bus;
import org.freedesktop.gstreamer.Caps;
import org.freedesktop.gstreamer.Element;
import org.freedesktop.gstreamer.ElementFactory;
import org.freedesktop.gstreamer.FlowReturn;
import org.freedesktop.gstreamer.Gst;
import org.freedesktop.gstreamer.Pipeline;
import org.freedesktop.gstreamer.Sample;
import org.freedesktop.gstreamer.StateChangeReturn;
import org.freedesktop.gstreamer.elements.AppSink;

import com.amazonaws.kinesisvideo.client.mediasource.MediaSource;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceSink;
import com.amazonaws.kinesisvideo.client.mediasource.MediaSourceState;
import com.amazonaws.kinesisvideo.common.exception.KinesisVideoException;
import com.amazonaws.kinesisvideo.producer.FrameFlags;
import com.amazonaws.kinesisvideo.producer.KinesisVideoFrame;
import com.amazonaws.kinesisvideo.producer.StreamInfo;
import com.amazonaws.kinesisvideo.producer.Tag;
import com.google.common.collect.Iterables;

/**
* A {@link MediaSource} implementation which reads from an RTSP stream (must be H264) using GStreamer.
 *
 * TODO: abstract GStreamer components so this can be used more generically.
 */
@ParametersAreNonnullByDefault
@NotThreadSafe
public class GStreamerRtspMediaSource implements MediaSource {
    private final URI rtspUri;
    private final Duration retentionPeriod;
    private final Tag[] tags;
    private Runner runner; // Encapsulate all mutability in a single Runner which is set when initialised

    public GStreamerRtspMediaSource(final URI rtspUri, final Duration retentionPeriod, final Tag[] tags) {
        this.rtspUri = requireNonNull(rtspUri);
        this.retentionPeriod = requireNonNull(retentionPeriod);
        this.tags = requireNonNull(tags).clone();
    }

    @Override
    @Nonnull
    public MediaSourceState getMediaSourceState() {
        if (runner != null && runner.isRunning()) return MediaSourceState.RUNNING;
        if (runner != null) return MediaSourceState.READY;
        return STOPPED;
    }

    @Override
    @Nonnull
    public StreamInfo getStreamInfo(final String streamName) {
        return new StreamInfo(
                0, // version
                streamName, // name
                STREAMING_TYPE_REALTIME,
                "video/h264", // contentType
                "", // kmsKeyId
                retentionPeriod.toNanos() / 100, // retention period
                false, // adaptive
                0, // maxLatency
                Duration.ofSeconds(2).toNanos() / 100, // fragmentDuration,
                true, // keyFrameFragmentation
                true, // frameTimecodes
                false, // absoluteFragmentTimes
                true, // fragmentAcks
                true, // recoverOnError
                "V_MPEG4/ISO/AVC", // codecId
                "kinesis_video", // trackName
                4 * 1024 * 1024, // avgBandwidthBps
                30, // frameRate
                Duration.ofSeconds(120).toNanos() / 100, // bufferDuration
                Duration.ofSeconds(40).toNanos() / 100, // replayDuration
                Duration.ofSeconds(30).toNanos() / 100, // connectionStalenessDuration
                Duration.ofMillis(1).toNanos() / 100, // timeCodeScale
                true, // recalculateMetrics
                null, // codecPrivateData (set on frame)
                tags, // tags
                NAL_ADAPTATION_FLAG_NONE // nalAdaptationFlags
        );
    }

    @Override
    public void initialize(final MediaSourceSink mediaSourceSink) {
        if (runner != null) throw new IllegalStateException("Already initialized");
        runner = new Runner(mediaSourceSink);
    }

    @Override
    public void start() {
        if (runner == null) throw new IllegalStateException("Started before initialized");
        runner.start();

    }

    @Override
    public void stop() {
        if (runner == null) return;
        runner.stop();
        runner = null;
    }

    @Override
    public boolean isStopped() {
        return getMediaSourceState() == STOPPED;
    }

    @Override
    public void free() {
        stop();
    }

    private class Runner {
        private final MediaSourceSink mediaSourceSink;
        private final Pipeline pipeline;
        private boolean codecPrivateDataSet = false;
        private int frameIndex = 0;

        private Runner(final MediaSourceSink mediaSourceSink) {
            this.mediaSourceSink = requireNonNull(mediaSourceSink);

            // Initialise GST
            Gst.init();

            // Pipeline
            pipeline = initPipeline();
        }

        void start() {
            // Attempt to play pipeline
            if (pipeline.play() == StateChangeReturn.FAILURE) throw new RuntimeException("Unable to set pipeline to playing state");

            // Enter main GStreamer loop
            Gst.main();
        }

        void stop() {
            pipeline.stop();
            pipeline.dispose();
            Gst.quit();
        }

        boolean isRunning() {
            return pipeline.isPlaying();
        }

        @Nonnull
        private Pipeline initPipeline() {
            // Create pipeline
            final var pipeline = new Pipeline("rtsp-kinesis-pipeline");
            pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> System.err.println(message));

            // Depay
            final var depay = ElementFactory.make("rtph264depay", "depay");

            // Filter
            final var filter = ElementFactory.make("capsfilter", "encoder_filter");
            filter.set("caps", Caps.fromString("video/x-h264,stream-format=avc,alignment=au"));

            // Source
            final var source = ElementFactory.make("rtspsrc", "source");
            source.set("location", rtspUri.toString());
            source.set("short-header", true);

            // AppSink
            final var appSink = new AppSink("appsink");
            appSink.set("emit-signals", true);
            appSink.connect(initNewSampleListener());

            // Connect source
            source.connect((Element.PAD_ADDED) (element, pad) -> {
                if (!Element.linkPads(source, pad.getName(), depay, "sink")) {
                    throw new IllegalStateException("Failed to link source");
                }
            });

            pipeline.addMany(source, depay, filter, appSink);
            if (!Element.linkMany(depay, filter, appSink)) {
                throw new IllegalStateException("Elements could not be linked");
            }

            return pipeline;
        }

        @Nonnull
        private AppSink.NEW_SAMPLE initNewSampleListener() {
            return appSink -> {
                // Pull sample
                final Sample sample = appSink.pullSample();

                // Handle configuring codec private data (when available)
                if (!codecPrivateDataSet) {
                    Optional.ofNullable(Iterables.getFirst(sample.getCaps().getStructure(0).getValues(Buffer.class, "codec_data"), null))
                            .map(buffer -> buffer.map(false))
                            .map(byteBuffer -> {
                                final var bytes = new byte[byteBuffer.remaining()];
                                byteBuffer.get(bytes);
                                return bytes;
                            })
                            .ifPresent(bytes -> {
                                codecPrivateDataSet = true;
                                try {
                                    mediaSourceSink.onCodecPrivateData(bytes);
                                } catch (KinesisVideoException e) {
                                    throw new RuntimeException(e);
                                }
                            });
                }

                // Get buffer from sample
                final Buffer buffer = sample.getBuffer();

                // Fix timestamps if necessary
                if (buffer.getPresentationTimestamp().isValid()) {
                    buffer.setDecodeTimestamp(sample.getBuffer().getPresentationTimestamp());
                } else {
                    buffer.setPresentationTimestamp(sample.getBuffer().getDecodeTimestamp());
                }

                // Set Kinesis flags
                final int kinesisFlags = ((buffer.getFlags() & DELTA_UNIT.intValue()) == DELTA_UNIT.intValue()) ? FrameFlags.FRAME_FLAG_NONE : FrameFlags.FRAME_FLAG_KEY_FRAME;

                // Create frame
                final var frame = new KinesisVideoFrame(
                        frameIndex++, // frameIndex
                        kinesisFlags, // flags
                        buffer.getDecodeTimestamp().toNanos() / 100, // decodeTs
                        buffer.getPresentationTimestamp().toNanos() / 100, // presentationTs
                        Duration.ofMillis(20).toNanos() / 100, // duration
                        buffer.map(false)); // data

                try {
                    mediaSourceSink.onFrame(frame);
                    return FlowReturn.OK;

                } catch (final KinesisVideoException e) {
                    e.printStackTrace(); // FIXME
                    return FlowReturn.ERROR;

                } finally {
                    buffer.unmap();
                    sample.dispose();
                }
            };
        }
    }
}
