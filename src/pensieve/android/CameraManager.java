package pensieve.android;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.hardware.Camera.Parameters;
import android.hardware.Camera.Size;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class CameraManager {
	private static final String TAG = "CameraManager";
	
	public static final int defaultCameraID = 0;
	public static final int defaultCameraWidth = 640, defaultCameraHeight = 480;
	
	private Context context = null;
	private Camera mCamera = null;
	private CameraPreview mPreview = null;
	private Size cameraSize = null; // can only be created with an enclosing Camera instance (WHAT?!)

	/**
	 * A simple camera preview class, based on:
	 * http://developer.android.com/guide/topics/media/camera.html
	 */
	class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
		private static final String TAG = "CameraPreview";

		Camera mCamera;
		List<Size> mSupportedPreviewSizes;
		Size mPreferredPreviewSize;
		Size mPreviewSize = null;

		/** Sets camera object (must be open) and starts preview when ready. */
		CameraPreview(Context context, Camera camera, Size size) {
			super(context);
			mCamera = camera;
			
			// Set preview size
			if (size != null) {
				mPreviewSize = size;
			}
			else {
				Parameters parameters = camera.getParameters();
				mSupportedPreviewSizes = parameters.getSupportedPreviewSizes();
				StringBuilder previewSizesStr = new StringBuilder();
				for (int i = 0; i < mSupportedPreviewSizes.size(); i++) {
					Size supportedSize = mSupportedPreviewSizes.get(i);
					previewSizesStr.append((i > 0 ? ", " : "") + "(" + supportedSize.width + ", " + supportedSize.height + ")");
				}
				Log.d(TAG, "Supported preview sizes: " + previewSizesStr.toString());
				if (mSupportedPreviewSizes.size() > 0) {
					mPreviewSize = mSupportedPreviewSizes.get(0); // pick first available size, by default
				}
				mPreferredPreviewSize = parameters.getPreferredPreviewSizeForVideo();
				if (mPreferredPreviewSize != null) {
					Log.d(TAG, "Preferred preview size: (" + mPreferredPreviewSize.width + ", " + mPreferredPreviewSize.height + ")");
					mPreviewSize = mPreferredPreviewSize; // if a preferred size is available, pick that
				}
			}
			if (mPreviewSize != null)
				Log.d(TAG, "Size: (" + mPreviewSize.width + ", " + mPreviewSize.height + ")");
			
			// Install a SurfaceHolder.Callback so we get notified when the
			// underlying surface is created and destroyed.
			SurfaceHolder holder = getHolder();
			holder.addCallback(this);
			holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
		}

		@Override
		public void surfaceCreated(SurfaceHolder holder) {
			/*
			// The Surface has been created, now tell the camera where to draw the preview.
			try {
				mCamera.setPreviewDisplay(holder);
				mCamera.startPreview();
			} catch (IOException e) {
				Log.e(TAG, "Error setting camera preview: " + e);
			}
			*/
		}

		@Override
		public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
			// Check if preview surface exists
			if (holder.getSurface() == null){
				return;
			}
			
			/*
			// Stop preview before making changes (shouldn't be started anyways)
			try {
				mCamera.stopPreview(); // NOTE stopping will clear any callbacks
			} catch (Exception e){
				Log.w(TAG, "Tried to stop a non-existent preview (?): " + e);
			}
			*/

			// Set up camera parameters (now that surface size is known)
			Camera.Parameters parameters = mCamera.getParameters();
			if (mPreviewSize == null) {
				Log.d(TAG, "Setting camera preview size to: (" + w + ", " + h + ")");
				mPreviewSize = mCamera.new Size(w, h); // last try to get a meaningful preview size
			}
			else {
				Log.d(TAG, "Setting preview surface size to: (" + mPreviewSize.width + ", " + mPreviewSize.height + ")");
				holder.setFixedSize(mPreviewSize.width, mPreviewSize.height);
			}
			parameters.setPreviewSize(mPreviewSize.width, mPreviewSize.height);
			requestLayout();
			mCamera.setParameters(parameters);
			
			// Start preview (with new parameters, if any)
			try {
				mCamera.setPreviewDisplay(holder);
				mCamera.startPreview();
			} catch (IOException e) {
				Log.e(TAG, "Unable to start camera preview: " + e);
			}
		}

		@Override
		public void surfaceDestroyed(SurfaceHolder holder) {
			// Camera preview to be stopped and camera to be released externally
		}
	}

	/** Check if this device has a camera */
	public static boolean hasCamera(Context context) {
		if (context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)){
			return true;
		} else {
			return false;
		}
	}
	
	/** Find out how many cameras this device has. */
	public static int getNumCameras() {
		return Camera.getNumberOfCameras();
	}
	
	/** Convert an image in Android's native YUV NV21 format to JPEG. */
	public static byte[] convertImage_NV21_to_JPEG(byte[] imageNV21, int imageFormat, int width, int height, int quality) {
		final int imageSize = width * height;
		if (!(imageFormat == ImageFormat.NV21 || imageFormat == ImageFormat.YUY2) || imageNV21.length != (int) (1.5 * imageSize)) {
			Log.e(TAG, "[NV21 to JPEG] Incompatible image format or invalid YUV image; format: " + imageFormat + ", size: (" + width + ", " + height + "), pixels: " + imageSize + ", num_bytes: " + imageNV21.length + ", quality: " + quality);
			return null;
		}
		
		YuvImage yuvImage = new YuvImage(imageNV21, imageFormat, width, height, null);
		ByteArrayOutputStream imageJPEGStream = new ByteArrayOutputStream();
		if (yuvImage.compressToJpeg(new Rect(0, 0, width, height), quality, imageJPEGStream)) {
			byte[] imageJPEG = imageJPEGStream.toByteArray();
			Log.v(TAG, "[NV21 to JPEG] JPEG compression successful; " + (100 * imageJPEG.length / imageNV21.length) + "% of original (" + imageNV21.length + " to " + imageJPEG.length + " bytes) at quality: " + quality);
			return imageJPEG;
		}
		else {
			Log.e(TAG, "[NV21 to JPEG] JPEG compression failed");
			return null;
		}
	}
	
	/** Convert an image in Android's native YUV NV21 format to RGB. */
	public static byte[] convertImage_NV21_to_RGB(byte[] imageNV21, int imageFormat, int width, int height) {
		final int imageSize = width * height;
		if (imageFormat != ImageFormat.NV21 || imageNV21.length != (int) (1.5 * imageSize)) {
			Log.e(TAG, "[NV21 to RGB] Incompatible image format or invalid YUV image; format: " + imageFormat + ", size: (" + width + ", " + height + "), pixels: " + imageSize + ", num_bytes: " + imageNV21.length);
			return null;
		}

		final int ii = 0;
		final int ij = 0;
		final int di = +1;
		final int dj = +1;

		byte[] imageRGB = new byte[imageSize * 3];
		int k = 0;
		for (int i = 0, ci = ii; i < height; ++i, ci += di) {
			for (int j = 0, cj = ij; j < width; ++j, cj += dj) {
				int y = (0xff & ((int) imageNV21[ci * width + cj]));
				int v = (0xff & ((int) imageNV21[imageSize + (ci >> 1) * width + (cj & ~1) + 0]));
				int u = (0xff & ((int) imageNV21[imageSize + (ci >> 1) * width + (cj & ~1) + 1]));
				y = y < 16 ? 16 : y;

				int r = (int) (1.164f * (y - 16) + 1.596f * (v - 128));
				int g = (int) (1.164f * (y - 16) - 0.813f * (v - 128) - 0.391f * (u - 128));
				int b = (int) (1.164f * (y - 16) + 2.018f * (u - 128));

				r = r < 0 ? 0 : (r > 255 ? 255 : r);
				g = g < 0 ? 0 : (g > 255 ? 255 : g);
				b = b < 0 ? 0 : (b > 255 ? 255 : b);

				imageRGB[k++] = (byte) r;
				imageRGB[k++] = (byte) g;
				imageRGB[k++] = (byte) b;
			}
		}
		
		return imageRGB;
	}
	
	public CameraManager(Context context) {
		this.context = context;
	}

	/** Open camera device indicated by id (0 onwards), specify 0 or negative width/height to use preferred size. */
	public void open(int id, int width, int height) {
		if (id < 0)
			id = 0;
		else if (id >= getNumCameras())
			id = getNumCameras() - 1;
		
		mCamera = safeCameraOpen(id);
		if (isCameraOpen()) {
			if (width > 0 && height > 0) {
				cameraSize = mCamera.new Size(width, height);
			}
			mPreview = new CameraPreview(context, mCamera, cameraSize);
		}
	}
	
	public boolean isCameraOpen() {
		return mCamera != null;
	}
	
	public Camera getCamera() {
		return mCamera;
	}
	
	public CameraPreview getCameraPreview() {
		return mPreview;
	}
	
	public void release() {
		if (mCamera != null) {
			mCamera.stopPreview();
			mCamera.release();
			mCamera = null;
		}
	}
	
	private Camera safeCameraOpen(int id) {
		Camera c = null;
		try {
			release();
			c = Camera.open(id);
		} catch (Exception e) {
			Log.e(TAG, "Failed to open camera");
			e.printStackTrace();
		}
		return c;
	}
}
