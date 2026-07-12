Shared Audio Target - Media Source Fix
======================================

This overlay changes only the two JavaScript files involved in shared audio targeting.

Changes:
- Claiming or releasing the shared audio target no longer loads or plays any media.
- Removes the synthetic silent-WAV priming code that caused Chrome's
  "media resource ... was not suitable" error.
- Actual MP3 files are loaded only when a real batter-audio command is received
  or a manager explicitly presses the Play button.
- Improves ownership text:
    * "Audio controller: this device"
    * "Audio controller: <manager>"
    * default audio-mode explanation

No Java, database, or Flyway changes are included.
