package com.example.fsl;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private PreviewView previewView;
    private TextView tvStatus;
    private Button btnStart;
    private Button btnStop;

    private ProcessCameraProvider cameraProvider;
    private Preview activePreview;
    private ImageAnalysis imageAnalysis;

    // Model creation, detection, and cleanup use this single thread.
    private final ExecutorService detectionExecutor =
            Executors.newSingleThreadExecutor();

    private HandLandmarker handLandmarker;
    private long lastFrameTime = 0;

    private volatile boolean cameraRequested = false;
    private volatile int sessionId = 0;

    private final ActivityResultLauncher<String> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted && cameraRequested) {
                            startCamera(sessionId);
                        } else if (!granted) {
                            stopCamera();
                            tvStatus.setText(
                                    "Allow camera permission in app settings.");
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        ViewCompat.setOnApplyWindowInsetsListener(
                findViewById(R.id.main),
                (view, insets) -> {
                    Insets bars = insets.getInsets(
                            WindowInsetsCompat.Type.systemBars());

                    view.setPadding(
                            bars.left, bars.top,
                            bars.right, bars.bottom);

                    return insets;
                }
        );

        previewView = findViewById(R.id.previewView);
        tvStatus = findViewById(R.id.tvStatus);
        btnStart = findViewById(R.id.btnStart);
        btnStop = findViewById(R.id.btnStop);

        btnStart.setOnClickListener(view -> {
            cameraRequested = true;
            sessionId++;
            updateButtons(true);

            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED) {

                startCamera(sessionId);
            } else {
                permissionLauncher.launch(Manifest.permission.CAMERA);
            }
        });

        btnStop.setOnClickListener(view -> stopCamera());
    }

    private void startCamera(int session) {
        tvStatus.setText("Opening camera...");

        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);

        future.addListener(() -> {
            if (!isCurrentSession(session)) {
                return;
            }

            try {
                cameraProvider = future.get();

                CameraSelector selector;

                if (cameraProvider.hasCamera(
                        CameraSelector.DEFAULT_FRONT_CAMERA)) {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                } else if (cameraProvider.hasCamera(
                        CameraSelector.DEFAULT_BACK_CAMERA)) {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                } else {
                    throw new IllegalStateException("No camera available");
                }

                activePreview = new Preview.Builder().build();

                activePreview.setSurfaceProvider(
                        previewView.getSurfaceProvider());

                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(
                                ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(
                        detectionExecutor,
                        image -> analyzeFrame(image, session));

                cameraProvider.bindToLifecycle(
                        this, selector, activePreview, imageAnalysis);

                tvStatus.setText("Loading hand detector...");

            } catch (Exception exception) {
                Log.e("FSL", "Camera setup failed", exception);
                stopCamera();
                tvStatus.setText(
                        "Camera setup failed. Check Logcat.");
            }

        }, ContextCompat.getMainExecutor(this));
    }

    private void createHandDetector() {
        BaseOptions baseOptions = BaseOptions.builder()
                .setModelAssetPath("hand_landmarker.task")
                .build();

        HandLandmarker.HandLandmarkerOptions options =
                HandLandmarker.HandLandmarkerOptions.builder()
                        .setBaseOptions(baseOptions)
                        .setRunningMode(RunningMode.IMAGE)
                        .setNumHands(2)
                        .setMinHandDetectionConfidence(0.5f)
                        .setMinHandPresenceConfidence(0.5f)
                        .build();

        handLandmarker = HandLandmarker.createFromOptions(
                getApplicationContext(), options);
    }

    private void analyzeFrame(ImageProxy image, int session) {
        Bitmap original = null;
        Bitmap rotated = null;
        MPImage mpImage = null;

        try {
            if (!cameraRequested || session != sessionId) {
                return;
            }

            // Limit detection frequency for this initial prototype.
            long now = SystemClock.uptimeMillis();

            if (now - lastFrameTime < 150) {
                return;
            }

            lastFrameTime = now;

            if (handLandmarker == null) {
                createHandDetector();
            }

            original = image.toBitmap();

            Matrix rotation = new Matrix();
            rotation.postRotate(
                    image.getImageInfo().getRotationDegrees());

            rotated = Bitmap.createBitmap(
                    original,
                    0, 0,
                    original.getWidth(),
                    original.getHeight(),
                    rotation,
                    true
            );

            mpImage = new BitmapImageBuilder(rotated).build();

            HandLandmarkerResult result =
                    handLandmarker.detect(mpImage);

            int count = result.landmarks().size();

            String message;

            if (count == 0) {
                message = "No hands detected";
            } else if (count == 1) {
                message = "1 hand detected";
            } else {
                message = "2 hands detected";
            }

            runOnUiThread(() -> {
                if (isCurrentSession(session)) {
                    tvStatus.setText(message);
                }
            });

        } catch (Exception exception) {
            Log.e("FSL", "Hand detection failed", exception);

            runOnUiThread(() -> {
                if (isCurrentSession(session)) {
                    stopCamera();
                    tvStatus.setText(
                            "Hand detection failed. Check the model file "
                                    + "and Logcat.");
                }
            });

        } finally {
            if (mpImage != null) {
                mpImage.close();
            }

            if (rotated != null && rotated != original) {
                rotated.recycle();
            }

            if (original != null) {
                original.recycle();
            }

            // Always release the frame so the camera can continue.
            image.close();
        }
    }

    private boolean isCurrentSession(int session) {
        return cameraRequested
                && session == sessionId
                && !isFinishing()
                && !isDestroyed();
    }

    private void releaseCamera() {
        if (imageAnalysis != null) {
            imageAnalysis.clearAnalyzer();
        }

        if (cameraProvider != null) {
            if (activePreview != null) {
                cameraProvider.unbind(activePreview);
            }

            if (imageAnalysis != null) {
                cameraProvider.unbind(imageAnalysis);
            }
        }

        activePreview = null;
        imageAnalysis = null;
    }

    private void stopCamera() {
        cameraRequested = false;
        sessionId++;

        releaseCamera();
        tvStatus.setText("Camera stopped");
        updateButtons(false);
    }

    private void updateButtons(boolean started) {
        btnStart.setEnabled(!started);
        btnStop.setEnabled(started);
    }

    @Override
    protected void onDestroy() {
        cameraRequested = false;
        sessionId++;
        releaseCamera();

        // Close the model after any running detection finishes.
        detectionExecutor.execute(() -> {
            if (handLandmarker != null) {
                handLandmarker.close();
                handLandmarker = null;
            }
        });

        detectionExecutor.shutdown();
        super.onDestroy();
    }
}