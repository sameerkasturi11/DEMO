# SynaptiMesh Android Receiver

An Android Accessibility Service built to execute remote automation commands via MQTT for the SynaptiMesh EEG system.

## System Architecture Flow

The following flowchart outlines the complete execution pipeline from an EEG hardware trigger to a successful automation execution on the Android device.

```text
EEG Command
      │
      ▼
Python MQTT Publisher
      │
      ▼
Mosquitto MQTT Broker
      │
      ▼
Android MQTT Receiver
      │
      ▼
Receive RIGHT_SEARCH_ALBUM_PLAYLIST
      │
      ▼
Launch JioSaavn
(if not already running)
      │
      ▼
Open Search
      │
      ▼
Focus Search Box
      │
      ▼
Enter Search Query
"Let's Play - Anirudh Ravichander - Telugu"
      │
      ▼
Wait for Search Results
      │
      ▼
Select Playlist
"Let's Play - Anirudh Ravichander - Telugu"
      │
      ▼
Wait Until Playlist Page Loads
      │
      ▼
Search Accessibility Tree
for Valid Song Rows
      │
      ▼
Valid Song Found?
      │
 ┌────┴────┐
 │         │
Yes        No
 │         │
 ▼         ▼
Click     Perform
Song Row  ACTION_SCROLL_FORWARD
 │         │
 ▼         ▼
Verify    Refresh Accessibility Tree
 │         │
 └────┬────┘
      ▼
Song Found?
      │
 ┌────┴────┐
 │         │
Yes        No
 │         │
 ▼         ▼
Click     Find Anchor
Song Row  ("Let's Play", "Songs", "Fans")
 │         │
 ▼         ▼
Verify    Calculate Gesture Coordinates
 │         │
 └────┬────┘
      ▼
Execute dispatchGesture()
      │
      ▼
Verify Playback
(MediaController)
      │
 ┌────┴────┐
 │         │
Success   Failed
 │         │
 ▼         ▼
Send ACK  Send Failure ACK
SUCCESS   + Error Reason
      │
      ▼
Release Command Queue
      │
      ▼
Execute Next MQTT Command
      │
      ▼
RIGHT_PLAY_PAUSE
RIGHT_NEXTTRACK
RIGHT_PREVTRACK
RIGHT_VOLUMEUP
RIGHT_VOLUMEDOWN
RIGHT_RETURN_TO_HOME
```

## Features
- **Headless MQTT Integration**: Subscribes to automation commands silently in the background.
- **Advanced Accessibility Engine**: Bypasses UI limitations, parses dynamic Jetpack Compose trees, and utilizes robust raw hardware gestures when standard `AccessibilityNodeInfo` targeting fails.
- **Multi-Stage Playback Verification**: Ensures that media state transitions to `PLAYING` before returning a success acknowledgment back to the EEG interface.
