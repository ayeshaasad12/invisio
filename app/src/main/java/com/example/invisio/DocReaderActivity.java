package com.example.invisio;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.nio.ByteBuffer;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Hands-free document reader for blind users:
 *  - Analyzes live frames (sharpness, motion, edge density) and gives TTS guidance.
 *  - Auto-freezes & OCRs when aligned and steady.
 *  - Volume Up = rescan; Volume Down = toggle flashlight (torch).
 *  - Torch starts ON by default for better lighting.
 *  - Adds a 5s warm-up after start and after each rescan.
 */
public class DocReaderActivity extends AppCompatActivity {

    private static final String TAG = "DocReader";

    private PreviewView previewView;
    private ImageView frozenView;
    private ScrollView resultContainer;
    private TextView txtResult;

    private ProcessCameraProvider cameraProvider;
    private Preview preview;
    private ImageCapture imageCapture;
    private ImageAnalysis imageAnalysis;
    private ExecutorService cameraExecutor;

    // Keep a reference to control torch
    private androidx.camera.core.Camera boundCamera;
    private boolean torchOn = true; // default ON

    // State
    private final AtomicBoolean scanning = new AtomicBoolean(true);   // live analysis mode
    private final AtomicBoolean ocrRunning = new AtomicBoolean(false);
    private long lastGuidanceTs = 0L;

    // Thresholds (tune for your device/lighting)
    private static final double SHARPNESS_MIN = 60.0;    // Laplacian variance
    private static final double MOTION_MAX    = 8.0;     // mean abs diff
    private static final double EDGE_DENSITY_MIN = 0.10; // 10% edges
    private static final long   GUIDANCE_COOLDOWN_MS = 1600;

    // Warm-up control (5 seconds hands-off window)
    private static final long WARMUP_MS = 5000L;
    private volatile long warmupUntil = 0L;

    private TextToSpeech tts;
    private boolean ttsReady = false;

    // Permissions
    private final ActivityResultLauncher<String[]> permLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                Boolean camGranted = result.get(Manifest.permission.CAMERA);
                if (camGranted != null && camGranted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_doc_reader);

        previewView     = findViewById(R.id.previewView);
        frozenView      = findViewById(R.id.frozenView);
        resultContainer = findViewById(R.id.resultContainer);
        txtResult       = findViewById(R.id.txtResult);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // TTS
        tts = new TextToSpeech(this, status -> {
            ttsReady = (status == TextToSpeech.SUCCESS);
            if (ttsReady) {
                tts.setLanguage(Locale.US);
                tts.setSpeechRate(0.95f);
                // Initial cue will be spoken by startWarmup() once camera is ready
            }
        });

        // Permissions
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            permLauncher.launch(new String[]{ Manifest.permission.CAMERA });
        } else {
            startCamera();
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> providerFuture =
                ProcessCameraProvider.getInstance(this);

        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();

                preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder()
                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                        .build();

                imageAnalysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();
                imageAnalysis.setAnalyzer(cameraExecutor, this::analyzeFrame);

                CameraSelector selector = new CameraSelector.Builder()
                        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                        .build();

                cameraProvider.unbindAll();
                boundCamera = cameraProvider.bindToLifecycle(
                        this, selector, preview, imageCapture, imageAnalysis
                );

                // Torch ON by default
                if (boundCamera != null) {
                    boundCamera.getCameraControl().enableTorch(torchOn);
                }

                // Start the initial warm-up so user can align
                startWarmup();

            } catch (Exception e) {
                Log.e(TAG, "startCamera failed", e);
                Toast.makeText(this, "Camera start failed", Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ====== Analyzer: evaluates sharpness, motion, quick text proxy ======
    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyzeFrame(ImageProxy image) {
        // Drop frames during warmup or when scanning is disabled
        if (!scanning.get() || System.currentTimeMillis() < warmupUntil) {
            image.close();
            return;
        }
        try {
            GrayFrame gf = GrayFrame.from(image, 320);

            // Sharpness (Laplacian variance)
            double sharp = gf.laplacianVar();

            // Motion (mean abs diff vs previous)
            double motion = gf.motionScore();

            // Quick text presence proxy via edge density
            boolean quickText = gf.edgeDensity() > EDGE_DENSITY_MIN;

            // Guidance (throttled)
            long now = System.currentTimeMillis();
            if (now - lastGuidanceTs > GUIDANCE_COOLDOWN_MS) {
                lastGuidanceTs = now;
                if (sharp < SHARPNESS_MIN) {
                    speak("Hold steady or move closer.");
                } else if (motion > MOTION_MAX) {
                    speak("Hold the phone still.");
                } else if (!quickText) {
                    speak("Center the page in view.");
                }
            }

            // Auto-freeze if all conditions are good
            if (sharp >= SHARPNESS_MIN && motion <= MOTION_MAX && quickText && !ocrRunning.get()) {
                scanning.set(false);
                runOnUiThread(() -> {
                    Bitmap snap = snapshotPreview();
                    if (snap != null) {
                        frozenView.setImageBitmap(snap);
                        frozenView.setVisibility(View.VISIBLE);
                        resultContainer.setVisibility(View.VISIBLE);
                    }
                });
                captureAndOcr();
            }
        } catch (Throwable t) {
            Log.w(TAG, "analyzeFrame error", t);
        } finally {
            image.close();
        }
    }

    private Bitmap snapshotPreview() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                return previewView.getBitmap();
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private void captureAndOcr() {
        if (imageCapture == null) {
            // Fallback: OCR the preview snapshot if capture unavailable
            Bitmap snap = snapshotPreview();
            if (snap != null) ocrBitmap(snap, 0);
            return;
        }
        ocrRunning.set(true);
        imageCapture.takePicture(ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageCapturedCallback() {
                    @Override @OptIn(markerClass = ExperimentalGetImage.class)
                    public void onCaptureSuccess(ImageProxy image) {
                        try {
                            if (image.getFormat() != ImageFormat.YUV_420_888 || image.getImage() == null) {
                                Bitmap snap = snapshotPreview();
                                if (snap != null) {
                                    ocrBitmap(snap, 0);
                                } else {
                                    speak("Capture failed. Please try again.");
                                    resumeScanning();
                                }
                                return;
                            }
                            int rotation = image.getImageInfo().getRotationDegrees();
                            InputImage input = InputImage.fromMediaImage(image.getImage(), rotation);
                            runMlkitOcr(input);
                        } catch (Throwable t) {
                            Log.e(TAG, "captureAndOcr error", t);
                            speak("Something went wrong. Please try again.");
                            resumeScanning();
                        } finally {
                            image.close();
                        }
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        Log.e(TAG, "takePicture failed", exception);
                        speak("Capture failed. Please try again.");
                        resumeScanning();
                    }
                });
    }

    private void ocrBitmap(Bitmap bmp, int rotationDeg) {
        try {
            InputImage input = InputImage.fromBitmap(bmp, rotationDeg);
            runMlkitOcr(input);
        } catch (Throwable t) {
            Log.e(TAG, "ocrBitmap error", t);
            speak("OCR failed. Please try again.");
            resumeScanning();
        }
    }

    private void runMlkitOcr(InputImage input) {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                .process(input)
                .addOnSuccessListener(result -> {
                    String text = (result != null && result.getText() != null) ? result.getText().trim() : "";
                    txtResult.setText(text.isEmpty() ? "No text detected." : text);
                    if (text.isEmpty()) {
                        speak("No text detected. Adjust the page and try again.");
                        resumeScanning();
                    } else {
                        speak("Document scanned. " + text);
                        // remain frozen until rescan (Volume Up)
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "OCR failed", e);
                    speak("OCR failed. Please try again.");
                    resumeScanning();
                })
                .addOnCompleteListener(t -> ocrRunning.set(false));
    }

    private void resumeScanning() {
        runOnUiThread(() -> {
            frozenView.setImageBitmap(null);
            frozenView.setVisibility(View.GONE);
            resultContainer.setVisibility(View.GONE);
            txtResult.setText("");
        });
        // Start 5s warm-up instead of scanning immediately
        startWarmup();
    }

    private void startWarmup() {
        // Disable live analysis during warmup window
        scanning.set(false);
        warmupUntil = System.currentTimeMillis() + WARMUP_MS;

        // Speak guidance once at warmup start
        speak("Document reader is getting ready. Set the camera on the document. Align the camera on the document.");

        // Enable scanning after warm-up
        previewView.postDelayed(() -> {
            scanning.set(true);
            speak("Ready.");
        }, WARMUP_MS);
    }

    private void speak(String s) {
        if (!ttsReady || s == null) return;
        if (s.trim().length() < 2) return;
        tts.speak(s, TextToSpeech.QUEUE_FLUSH, null, "TTS_" + System.currentTimeMillis());
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
        super.onDestroy();
    }

    // ====== Accessibility hotkeys ======
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume Up: Rescan
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            speak("Rescanning.");
            resumeScanning();
            return true;
        }
        // Volume Down: Toggle flashlight
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            torchOn = !torchOn;
            if (boundCamera != null) {
                boundCamera.getCameraControl().enableTorch(torchOn);
                speak(torchOn ? "Flashlight on" : "Flashlight off");
            }
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    // ====== Lightweight grayscale frame wrapper for analysis ======
    private static class GrayFrame {
        final int w, h;
        final byte[] y;          // luma plane (downscaled)
        byte[] prev;             // previous frame luma (same size), for motion

        private static GrayFrame last; // keep last across calls

        static GrayFrame from(ImageProxy image, int targetW) {
            ImageProxy.PlaneProxy[] planes = image.getPlanes();
            if (planes == null || planes.length == 0) throw new IllegalStateException("No planes");
            ByteBuffer yb = planes[0].getBuffer();
            int iw = image.getWidth(), ih = image.getHeight();

            // Downscale nearest-neighbor to ~targetW keeping aspect
            int tw = Math.min(targetW, iw);
            int th = Math.max(1, (int) (ih * (tw / (float) iw)));

            byte[] yDown = new byte[tw * th];

            // Row/stride handling for Y plane
            int rowStride = planes[0].getRowStride();
            byte[] yFull = new byte[Math.min(rowStride * ih, yb.remaining())];
            yb.rewind();
            yb.get(yFull, 0, yFull.length);

            for (int yy = 0; yy < th; yy++) {
                int srcY = (int) (yy * (ih / (float) th));
                int srcRow = srcY * rowStride;
                for (int xx = 0; xx < tw; xx++) {
                    int srcX = (int) (xx * (iw / (float) tw));
                    int idx = srcRow + srcX;
                    if (idx >= 0 && idx < yFull.length) {
                        yDown[yy * tw + xx] = yFull[idx];
                    } else {
                        yDown[yy * tw + xx] = 0;
                    }
                }
            }

            GrayFrame gf = new GrayFrame(tw, th, yDown);
            if (last != null && last.w == tw && last.h == th) {
                gf.prev = last.y;
            }
            last = gf;
            return gf;
        }

        GrayFrame(int w, int h, byte[] y) { this.w = w; this.h = h; this.y = y; }

        double laplacianVar() {
            long sum = 0, sum2 = 0;
            int count = 0;
            for (int j = 1; j < h - 1; j++) {
                for (int i = 1; i < w - 1; i++) {
                    int c  = y[j*w + i] & 0xFF;
                    int l  = y[j*w + (i-1)] & 0xFF;
                    int r  = y[j*w + (i+1)] & 0xFF;
                    int u  = y[(j-1)*w + i] & 0xFF;
                    int d  = y[(j+1)*w + i] & 0xFF;
                    int lap = 4*c - l - r - u - d;
                    sum += lap;
                    sum2 += (long) lap * lap;
                    count++;
                }
            }
            if (count == 0) return 0;
            double mean = sum / (double) count;
            return (sum2 / (double) count) - mean * mean;
        }

        double motionScore() {
            if (prev == null || prev.length != y.length) return 0;
            long acc = 0;
            for (int i = 0; i < y.length; i += 4) { // sample for speed
                int a = y[i] & 0xFF;
                int b = prev[i] & 0xFF;
                acc += Math.abs(a - b);
            }
            return acc / (double) (y.length / 4);
        }

        double edgeDensity() {
            int edges = 0, total = 0;
            for (int j = 1; j < h - 1; j += 2) {
                for (int i = 1; i < w - 1; i += 2) {
                    int c  = y[j*w + i] & 0xFF;
                    int r  = y[j*w + (i+1)] & 0xFF;
                    int d  = y[(j+1)*w + i] & 0xFF;
                    if (Math.abs(c - r) > 20 || Math.abs(c - d) > 20) edges++;
                    total++;
                }
            }
            if (total == 0) return 0;
            return edges / (double) total;
        }
    }
}
