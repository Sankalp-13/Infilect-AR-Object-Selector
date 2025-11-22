
<h1>AR Object Selector — MediaPipe + ARCore + SceneView</h1>

AR Object Selector is an Android app that detects objects on a retail shelf using MediaPipe Object Detection, and then anchors a 3D AR checkmark using ARCore + SceneView.

It automatically remembers which items were selected and prevents duplicates using real-world 3D distance filtering.

 <img width="401" height="390" alt="image" src="https://github.com/user-attachments/assets/556749c7-b7b1-44cd-8f48-0f9e570662cd" />
 

- Checkmarks remain anchored as the user moves
  
- Real-time object detection (MediaPipe EfficientDet Lite)

- Processes every camera frame and draws bounding boxes on screen.

- Every detected object is selected.

- Each selected object gets a 3D checkmark anchored to the shelf surface.

- Duplicate prevention using real-world distance

<h3>Demo</h3>

Flow:

1. Camera detects products

2. Each bounding box is converted to a screen coordinate

3. A raycast places a checkmark on the shelf

4. If the new checkmark is too close to an existing one → skip

https://github.com/user-attachments/assets/aea96cd3-533d-4143-9c48-2a5e84b34041

https://github.com/user-attachments/assets/7663cc2d-52ba-4db4-b218-bb9543842e5f




