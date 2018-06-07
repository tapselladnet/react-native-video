## react-native-video-tapsell

this package is a fork of react-native-video package and currently only supports android

A `<Video>` component for react-native with the ability to play pre-roll ads

Requires react-native >= 0.40.0

### Add it to your project

Run `npm i -S react-native-video-tapsell`

#### Android

Run `react-native link` to link the react-native-video library.

Or if you have trouble, make the following additions to the given files manually:

**android/settings.gradle**

```gradle
include ':react-native-video-tapsell'
project(':react-native-video-tapsell').projectDir = new File(rootProject.projectDir, '../node_modules/react-native-video-tapsell/android-exoplayer')
```

**android/app/build.gradle**

```gradle
dependencies {
   ...
   compile project(':react-native-video-tapsell')
}
```

**MainApplication.java**

On top, where imports are:

```java
import com.brentvatne.react.ReactVideoPackage;
```

Add the `ReactVideoPackage` class to your list of exported packages.

```java
@Override
protected List<ReactPackage> getPackages() {
    return Arrays.asList(
            new MainReactPackage(),
            new ReactVideoPackage()
    );
}
```
## Usage

```javascript
// Within your render function, assuming you have a file called
// "background.mp4" in your project. You can include multiple videos
// on a single screen if you like.

<Video source={{uri: "background"}}   // Can be a URL or a local file.
       ref={(ref) => {
         this.player = ref
       }}                                      // Store reference
       rate={1.0}                              // 0 is paused, 1 is normal.
       volume={1.0}                            // 0 is muted, 1 is normal.
       muted={false}                           // Mutes the audio entirely.
       paused={false}                          // Pauses playback entirely.
       resizeMode="cover"                      // Fill the whole screen at aspect ratio.*
       repeat={true}                           // Repeat forever.
       playInBackground={false}                // Audio continues to play when app entering background.
       playWhenInactive={false}                // [iOS] Video continues to play when control or notification center are shown.
       ignoreSilentSwitch={"ignore"}           // [iOS] ignore | obey - When 'ignore', audio will still play with the iOS hard silent switch set to silent. When 'obey', audio will toggle with the switch. When not specified, will inherit audio settings as usual.
       progressUpdateInterval={250.0}          // [iOS] Interval to fire onProgress (default to ~250ms)
       instreamAdInfo={{ adTagUrl: this.state.adTagUrl, adLang: this.state.adLang }}
       //adTagUrl: any vast tag url, adLang: for example "en"
       style={styles.backgroundVideo} />

// Later to trigger fullscreen
this.player.presentFullscreenPlayer()
// Disable fullscreen
this.player.dismissFullscreenPlayer()

// To set video position in seconds (seek)
this.player.seek(0)

// Later on in your styles..
var styles = StyleSheet.create({
  backgroundVideo: {
    position: 'absolute',
    top: 0,
    left: 0,
    bottom: 0,
    right: 0,
  },
});
```
**MIT Licensed**
