## kinesis-video-gstreamer-java

Publish GStreamer streams to Kinesis Video, implemented in Java.

This code is more or less analogous to the [kinesis-video-gst-demo](https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp/tree/master/kinesis-video-gst-demo)
provided in [amazon-kinesis-video-streams-producer-cpp](https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-cpp), except it's written in pure
Java (well, it depends on GStreamer and the native Kinesis bindings, but the code is all Java).

This code depends on the [amazon-kinesis-video-streams-producer-sdk-java](https://github.com/awslabs/amazon-kinesis-video-streams-producer-sdk-java), and simply 
implements a `MediaSource` which can read from GStreamer.

### What you get

* I've only implemented H264 RTSP streaming (because my use case was publishing streams from a security camera which uses H264 and emits a RTSP stream).
  * Coming soon I intend to genericify the GStreamer code so you can plug any GST `Pipeline` in. Or feel free to do that yourself.
* There's a demo (`GStreamerRtspPublisherDemo`) which should work.

### Caveats

* This currently depends on some code which isn't actually available. Specifically:
  1. I [forked](https://github.com/rikbrown/amazon-kinesis-video-streams-producer-sdk-java) **amazon-kinesis-video-streams-producer-sdk-java** because it didn't 
     really support making custom implementations of `MediaSource` (hopefully this will be merged in soon).
  1. That library isn't actually published to Maven yet so to get this project to build you'll need to jump through some hoops described below. 

##### Kotlin? 😢

I actually wrote this in Kotlin first. I'll provide some Kotlin code soon. I wanted a Java-based demo so AWS could refer to it though. Hey, I used Java 10 and `var`
at least?

### Running the demo

#### Prerequisites

* Gradle (`brew install gradle`)
* Maven (`brew install maven`)
* GStreamer (`brew install gstreamer gst-plugins-base gst-libav gst-plugins-bad gst-plugins-good gst-plugins-ugly`)

#### Steps

1. Clone a local copy of my forked version of [amazon-kinesis-video-streams-producer-sdk-java](https://github.com/rikbrown/amazon-kinesis-video-streams-producer-sdk-java)
1. Install it into your local Maven repository (`mvn install` from its directory)
1. Clone this repository
1. Run the demo!

   `gradle -Paws.accessKeyId=<accessKeyId>> -Paws.secretKey=<secretKey> -PstreamName=<KinesisStreamName> -PrtspUri=<rtsp://whateverYourRtspUriHappensToBe> runRtspDemo`

