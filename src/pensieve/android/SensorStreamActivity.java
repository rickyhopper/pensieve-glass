package pensieve.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;
import org.zeromq.ZMQ;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class SensorStreamActivity extends Activity {
	public static final String TAG = "SensorStream";

	private boolean silent = true; // suppress all audio output
	private TextToSpeech tts = null;
	private boolean ttsReady = false;
	
	private SensorManager sensorManager = null;
	private final float[] rotationMatrix = new float[16];
	private final float[] orientation = new float[9];
	private float heading, lastHeading;
	public static final float epsilonHeading = 1.f; // degrees (?), minimum significant change for reporting
	private float pitch, lastPitch;
	public static final float epsilonPitch = 1.f; // degrees (?), minimum significant change for reporting
	private boolean hasInterference; // whether the magnetic (compass) sensor is receiving interference (a guess)
//	private PostSensorDataThread postSensorDataThread = null;
	
	private CameraManager cameraManager;
	private int cameraId = CameraManager.defaultCameraID; // camera to open (0 is usually back camera)
	private int cameraWidth = CameraManager.defaultCameraWidth, cameraHeight = CameraManager.defaultCameraHeight; // pass 0 or -1 to use device-preferred size
//	private PostCameraImageThread postCameraImageThread = null; // must do network-heavy stuff in non-UI thread
	private byte imageHeaderDataSep = '\n'; // used to separate image header from data in combined packet mode; must be byte or byte[]
	private String imageFormat = "JPEG"; // "RGB", "JPEG" etc. as supported by CameraManager.convertImage_XXX_to_YYY() methods
	private int jpegQuality = 90;
	
	// ZMQ components (TODO use separate ZMQClientThread class)
	private String serverAddress = null; // leave null to read from resources; or any endpoint e.g.: "tcp://192.168.1.106:61445", "tcp://honeydew.csc.ncsu.edu:61445"; for emulator to host: "tcp://10.0.2.2:61445"
	private ZMQ.Context context = null;
	private ZMQ.Socket socket = null;
	private final Object socketLock = new Object();
	private volatile boolean serverAvailable = false;
	
	// audio stuff
	private MediaRecorder rec[] = new MediaRecorder[16];
	private String baseAudioPath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/sound";
	private int sampleRate = 16000;
	private String audioFormat = "mpeg4";
	private int recorderCount = 0;
	private int numRecorders = 16;
	
	// audio timer
	private Timer audioTimer = new Timer();
	private TimerTask timerTask = new TimerTask() {
		public void run() {
			PostAudioSnippetThread audioThread = new PostAudioSnippetThread();
			audioThread.start();
		}
	};
	
	// UI elements
	private FrameLayout cameraPreviewLayout;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sensor_stream);
		
		// Keep screen on
		Window myWindow = getWindow();
		myWindow.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		myWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		myWindow.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		//myWindow.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
		// Alternate method: Use a wake lock (NOTE requires manifest permission: android.permission.WAKE_LOCK")
		//wakeLock = ((PowerManager) getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);
		//wakeLock.acquire(); // in onResume()
		//wakeLock.release(); // in onPause()
		
		// make file for audio
		
		
		//audio
		for (int i = 0; i < numRecorders; i++) {
			rec[i] = new MediaRecorder();
			rec[i].setAudioSource(0);
			rec[i].setAudioChannels(2);
		    rec[i].setAudioEncodingBitRate(0x17700);
		    rec[i].setAudioSamplingRate(16000);
		    rec[i].setMaxDuration(0);
		    rec[i].setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		    rec[i].setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		    rec[i].setOutputFile(getAudioPath(i));
		    rec[i].setMaxDuration(-1);
			//rec.setAudioSamplingRate(sampleRate);
			
			try {
				rec[i].prepare();
			} catch (IOException e) {
				Log.e("log", "oh god we're going down ...\n" + e);
			}
		}
		
		rec[recorderCount].start();
		
		//schedule audio timer for every .5 seconds
		audioTimer.scheduleAtFixedRate(timerTask, 2000, 2000);
		
		// Get UI elements
		cameraPreviewLayout = (FrameLayout) findViewById(R.id.cameraPreviewLayout);

		// Initialize TTS engine
		if (!silent) {
			tts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
				@Override
				public void onInit(int status) {
					tts.speak("Ready", TextToSpeech.QUEUE_FLUSH, null);
					Log.d(TAG, "onCreate(): TTS initialized");
					ttsReady = true;
				}
			});
		}

		// Initialize sensor managers
//		sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
/*		if (CameraManager.hasCamera(this)) {
			Log.d(TAG, "onCreate(): Device has " + CameraManager.getNumCameras() + " cameras");
			cameraManager = new CameraManager(this);
		}
		else
			Log.w(TAG, "onCreate(): Device has no camera");
*/		
		Log.d(TAG, "onCreate(): Activity created");
	}

	@Override
	protected void onResume() {
		super.onResume();
		if(ttsReady) tts.speak("Resumed", TextToSpeech.QUEUE_FLUSH, null);
		
		// Open network channels (use non-UI threads since these can take time)
		(new Thread() {
			public void run() {
				openZMQ();
				pingZMQServer(); // [test] ping ZMQ server
			};
		}).start();
		
/*		// Activate managers, register listeners and callbacks
		// * Rotation vector: For orientation
		lastHeading = -1;
		lastPitch = -1;
		sensorManager.registerListener(sensorListener,
				sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR),
				SensorManager.SENSOR_DELAY_UI);
		// NOTE The rotation vector sensor doesn't give us accuracy updates, so we observe the
		// magnetic field sensor solely for those.
		sensorManager.registerListener(sensorListener,
				sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD),
				SensorManager.SENSOR_DELAY_UI);
/*		
		// * Camera
		if (cameraManager != null) {
			// Open camera
			cameraManager.open(cameraId, cameraWidth, cameraHeight);
			if (cameraManager.isCameraOpen()) {
				// Add camera preview to layout
				cameraPreviewLayout.addView(cameraManager.getCameraPreview());
				// Set preview callback
				cameraManager.getCamera().setPreviewCallback(onPreview);
				Log.d(TAG, "onResume(): Camera open; size: (" + cameraWidth + ", " + cameraHeight + "), pixels: " + (cameraWidth * cameraHeight));
			}
		}
*/	}

	@Override
	protected void onPause() {
		if(ttsReady) tts.speak("Paused", TextToSpeech.QUEUE_FLUSH, null);
		
		// Remove listeners
//		sensorManager.unregisterListener(sensorListener);
/*		if (cameraManager != null && cameraManager.isCameraOpen()) {
			cameraManager.getCamera().setPreviewCallback(null);
			cameraPreviewLayout.removeAllViews();
			cameraManager.release();
			Log.d(TAG, "Camera closed");
		}
*/		
		// Close network channels (also on non-UI threads)
		(new Thread() {
			public void run() {
				closeZMQ();
			}
		}).start();
		
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		// Release resources
		if (ttsReady) {
			tts.speak("Bye!", TextToSpeech.QUEUE_FLUSH, null);
			tts.shutdown();
		}
		tts = null;
		
//		sensorManager = null;
//		cameraManager = null;
		
		socket = null;
		context = null;
		
		super.onDestroy();
	}
	
	private void openZMQ() {
		// NOTE Should run this from non-UI thread
		// * Get server endpoint address from resources
		if (serverAddress == null)
			serverAddress = getResources().getString(R.string.server_address);
		
		// * Create ZMQ context and socket, set socket opts and connect to server
		synchronized(socketLock) {
			context = ZMQ.context(1);
			socket = context.socket(ZMQ.REQ);
			socket.setSendTimeOut(1500); // TODO fix hardcoded values
			socket.setReceiveTimeOut(10000);
			
			Log.v(TAG, "openZMQ(): Connecting to " + serverAddress + "..."); // useful for debugging, in case connect() fails
			socket.connect(serverAddress);
			Log.d(TAG, "openZMQ(): Connected to " + serverAddress);
			//serverAvailable = true; // NOTE no actual checking is performed here; pingZMQServer() does that
		}
	}
	
	private void closeZMQ() {
		// Close ZMQ socket and terminate context
		synchronized(socketLock) {
			serverAvailable = false;
			if (socket != null) {
				socket.close();
			}
			if (context != null) {
				context.term();
			}
		}
	}
	
	private void pingZMQServer() {
		// NOTE Should run this from non-UI thread
		synchronized(socketLock) {
			if (socket == null)
				return;
			serverAvailable = false; // assume unavailable till a ping succeeds
			
			// * Prepare a ping request
			JSONObject requestObj = new JSONObject();
			try {
				requestObj.put("type", "handshake");
				requestObj.put("value", "ping");
			} catch (JSONException e) {
				Log.e(TAG, "pingServer(): Failed to prepare JSON request: " + e);
			}
			String request = requestObj.toString();
			
			// * Send request and receive response
			Log.d(TAG, "pingServer(): Sending request: " + request);
			if(socket.send(request)) {
				String reply = socket.recvStr();
				Log.d(TAG, "pingServer(): Received (raw): " + reply);
				if(reply != null) {
					try {
						JSONObject replyObj = new JSONObject(reply);
						Log.d(TAG, "pingServer(): Received (obj): " + replyObj.toString());
						String status = replyObj.getString("status");
						Log.d(TAG, "pingServer(): Reply status: " + status);
						serverAvailable = true;
					} catch (JSONException e) {
						Log.e(TAG, "pingServer(): Failed to decode JSON reply: " + e);
					}
				}
				else {
					Log.w(TAG, "pingServer(): ZMQ server not available (?)");
					serverAvailable = false; // NOTE actual check, but how reliable is it?
				}
			}
			else {
				Log.e(TAG, "pingServer(): Failed to send");
			}
		}
	}

	/** A simple structure for all sensor data to be streamed. */
/*	class SensorData {
		public static final String TAG = "SensorData";
		
		JSONObject obj; // internal JSON object that actually stores fields (TODO isn't there a decorator pattern for this type of serialization?)
		
		public SensorData() throws JSONException {
			obj = new JSONObject();
			obj.put("type", "sensor");
		}
		
		public SensorData(float heading, float pitch) throws JSONException {
			this();
			obj.put("heading", heading);
			obj.put("pitch", pitch);
		}
		
		public JSONObject getJSONObject() { return obj; }
		public String toString() { return obj.toString(); }
	}
	
	/** An object that listens to various sensor updates and reports them. */
/*	private SensorEventListener sensorListener = new SensorEventListener() {
		@Override
		public void onAccuracyChanged(Sensor sensor, int accuracy) {
			if (sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
				hasInterference = (accuracy < SensorManager.SENSOR_STATUS_ACCURACY_HIGH);
				Log.d(TAG, "sensorListener.onAccuracyChanged() [TYPE_MAGNETIC_FIELD]: Interference? " + hasInterference);
			}
		}

		@Override
		public void onSensorChanged(SensorEvent event) {
			if (event.sensor.getType() == Sensor.TYPE_ROTATION_VECTOR) {
				// Get the current heading from the sensor, then notify the listeners of the
				// change.
				SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values);
				SensorManager.remapCoordinateSystem(rotationMatrix, SensorManager.AXIS_X,
						SensorManager.AXIS_Z, rotationMatrix);
				SensorManager.getOrientation(rotationMatrix, orientation);

				// Store the pitch (used to display a message indicating that the user's head
				// angle is too steep to produce reliable results.
				pitch = (float) Math.toDegrees(orientation[1]);

				// Convert the heading (which is relative to magnetic north) to one that is
				// relative to true north, using the user's current location to compute this.
				float magneticHeading = (float) Math.toDegrees(orientation[0]);
				heading = MathUtils.mod(magneticHeading, 360.0f); // NOTE this does not compute true north and does not take into account offset introduced by the movable Glass arm (see GDK Compass sample for more accurate calculations)

				// TODO Update UI
				//Log.d(TAG, "sensorListener.onSensorChanged() [TYPE_ROTATION_VECTOR]: heading: " + heading + ", pitch: " + pitch);
				
				// Check if heading or pitch has changed beyond threshold (we don't want to report all minute changes); if so, report it
				if (Math.abs(heading - lastHeading) > epsilonHeading || Math.abs(pitch - lastPitch) > epsilonPitch) {
					lastHeading = heading;
					if (serverAvailable && !(postSensorDataThread != null && postSensorDataThread.isAlive())) {
						try {
							postSensorDataThread = new PostSensorDataThread(new SensorData(heading, pitch));
							postSensorDataThread.start();
						} catch (JSONException e) {
							Log.e(TAG, "[Sensor] Error creating data packet: " + e);
						}
					}
				}
			}
		}
	};

	class PostSensorDataThread extends Thread {
		SensorData data;
		
		public PostSensorDataThread(SensorData data) {
			this.data = data;
		}
		
		@Override
		public void run() {
			// Prepare request object and send sensor data
			synchronized(socketLock) {
				String request = data.toString();
				Log.v(TAG, "[Sensor] Sending data: " + request);
				if(socket.send(request)) {
					String reply = socket.recvStr();
					Log.v(TAG, "[Sensor] Received reply (raw): " + reply);
					if(reply != null) {
						try {
							JSONObject replyObj = new JSONObject(reply);
							Log.v(TAG, "[Sensor] Received reply (obj): " + replyObj.toString());
							int status = replyObj.getInt("status");
							if (status == 200) {
								Log.d(TAG, "[Sensor] Reply status OK: " + status);
							} else {
								Log.w(TAG, "[Sensor] Reply status not favorable: " + status);
							}
						} catch (JSONException e) {
							Log.e(TAG, "[Sensor] Failed to decode JSON reply: " + e);
						}
					} else {
						Log.e(TAG, "[Sensor] Null reply (no response?)");
					}
				} else {
					Log.e(TAG, "[Sensor] Failed to send data");
				}
			}
		}
	}
	/*
	private PreviewCallback onPreview = new PreviewCallback() {
		@Override
		public void onPreviewFrame(byte[] frame, Camera camera) {
			if (serverAvailable && !(postCameraImageThread != null && postCameraImageThread.isAlive())) {
				postCameraImageThread = new PostCameraImageThread(frame, camera);
				postCameraImageThread.start();
			}
		}
	};
	
	class PostCameraImageThread extends Thread {
		byte[] frame;
		Camera camera;
		
		public PostCameraImageThread(byte[] frame, Camera camera) {
			this.frame = frame;
			this.camera = camera;
		}
		
		public void run() {
			// Get frame info (TODO can be cached)
			Size frameSize = camera.getParameters().getPreviewSize();
			int frameFormat = camera.getParameters().getPreviewFormat();
			Log.v(TAG, "[Frame] size: (" + frameSize.width + ", " + frameSize.height + "), pixels: " + (frameSize.width * frameSize.height) + ", num_bytes: " + frame.length + ", format: " + frameFormat);

			// Process frame, if required
			byte[] image = null;
			if (imageFormat.equalsIgnoreCase("RGB"))
				image = CameraManager.convertImage_NV21_to_RGB(frame, frameFormat, frameSize.width, frameSize.height);
			else if (imageFormat.equalsIgnoreCase("JPEG"))
				image = CameraManager.convertImage_NV21_to_JPEG(frame, frameFormat, frameSize.width, frameSize.height, jpegQuality);
			
			if (image == null)
				return;

			// Send over ZMQ (header as JSON-encoded string and raw image data concatenated with a separator)
			// * TODO Check if ZMQ socket is ready to send
			synchronized(socketLock) {
				try {
					// * Prepare image header request
					JSONObject requestObj = new JSONObject();
					requestObj.put("type", "image");
					requestObj.put("num_bytes", image.length);
					requestObj.put("width", frameSize.width);
					requestObj.put("height", frameSize.height);
					requestObj.put("format", imageFormat);
					String request = requestObj.toString();
					
					// * Send image header request and data concatenated together with a known separator
					ByteArrayOutputStream combinedRequestStream = new ByteArrayOutputStream( );
					combinedRequestStream.write(request.getBytes());
					combinedRequestStream.write(imageHeaderDataSep);
					combinedRequestStream.write(image);
					Log.v(TAG, "[Frame] Sending image header + data packet");
					if(socket.send(combinedRequestStream.toByteArray())) {
						String reply = socket.recvStr();
						Log.v(TAG, "[Frame] Received reply (raw): " + reply);
						if(reply != null) {
							try {
								JSONObject replyObj = new JSONObject(reply);
								Log.v(TAG, "[Frame] Received reply (obj): " + replyObj.toString());
								int status = replyObj.getInt("status");
								if (status == 200) {
									Log.d(TAG, "[Frame] Reply status OK: " + status);
								} else {
									Log.w(TAG, "[Frame] Reply status not favorable: " + status);
								}
							} catch (JSONException e) {
								Log.e(TAG, "[Frame] Failed to decode JSON reply: " + e);
							}
						} else {
							Log.e(TAG, "[Frame] Null reply (no response?)");
						}
					} else {
						Log.e(TAG, "[Frame] Failed to send image header + data");
					}
				} catch (JSONException e) {
					Log.e(TAG, "[Frame] Failed to prepare JSON header: " + e);
				} catch (IOException e) {
					Log.e(TAG, "[Frame] Failed to prepare image header + data packet: " + e);
				}
			}
		}
	}
	*/
	private String getAudioPath(int num) {
		return baseAudioPath + num + ".m4a";
	}
	
	class RefreshMediaRecorderThread extends Thread {
		int recNum;
		
		public RefreshMediaRecorderThread(int num) {
			recNum = num;
		}
		
		public void run() {
			rec[recNum].setAudioSource(0);
			rec[recNum].setAudioChannels(2);
		    rec[recNum].setAudioEncodingBitRate(0x17700);
		    rec[recNum].setAudioSamplingRate(16000);
		    rec[recNum].setMaxDuration(0);
		    rec[recNum].setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
		    rec[recNum].setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
		    rec[recNum].setOutputFile(getAudioPath(recNum));
		    rec[recNum].setMaxDuration(-1);
			//rec.setAudioSamplingRate(sampleRate);
			
			try {
				rec[recNum].prepare();
			} catch (IOException e) {
				Log.e("log", "oh god we're going down ...\n" + e);
			}
		}
	}
	
	class PostAudioSnippetThread extends Thread {
		
		public PostAudioSnippetThread() {
		}
		
		public void run() {
			Log.d(TAG, "running PostAudioSnippetThread");
			
			//stop recording
			rec[recorderCount].stop();
			
			//open the audio file to send to the server
			File f = new File(getAudioPath(recorderCount));
			byte[] bytes;
			recorderCount = (recorderCount+(numRecorders-1)) % numRecorders;
			rec[recorderCount].start();
			try {
				bytes = FileUtils.readFileToByteArray(f);
				// Send over ZMQ (header as JSON-encoded string and raw image data concatenated with a separator)
				// * TODO Check if ZMQ socket is ready to send
				synchronized(socketLock) {
					try {
						// * Prepare image header request
						JSONObject requestObj = new JSONObject();
						requestObj.put("type", "stt"); //speech to text
						requestObj.put("num_bytes", bytes.length);
						requestObj.put("sample_rate", sampleRate);
						requestObj.put("format", audioFormat); //unhardcode
						String request = requestObj.toString();
						Log.d(TAG, "[STT] Request: " + request);
						
						// * Send image header request and data concatenated together with a known separator
						ByteArrayOutputStream combinedRequestStream = new ByteArrayOutputStream( );
						combinedRequestStream.write(request.getBytes());
						combinedRequestStream.write(imageHeaderDataSep);
						combinedRequestStream.write(bytes);
						Log.v(TAG, "[STT] Sending audio header + data packet");
						if(socket.send(combinedRequestStream.toByteArray())) {
							String reply = socket.recvStr();
							Log.v(TAG, "[STT] Received reply (raw): " + reply);
							if(reply != null) {
								try {
									JSONObject replyObj = new JSONObject(reply);
									Log.v(TAG, "[STT] Received reply (obj): " + replyObj.toString());
									int status = replyObj.getInt("status");
									if (status == 200) {
										Log.d(TAG, "[STT] Reply status OK: " + status);
									} else {
										Log.w(TAG, "[STT] Reply status not favorable: " + status);
									}
								} catch (JSONException e) {
									Log.e(TAG, "[STT] Failed to decode JSON reply: " + e);
								}
							} else {
								Log.e(TAG, "[STT] Null reply (no response?)");
							}
						} else {
							Log.e(TAG, "[STT] Failed to send audio header + data");
						}
					} catch (JSONException e) {
						Log.e(TAG, "[STT] Failed to prepare JSON header: " + e);
					} catch (IOException e) {
						Log.e(TAG, "[STT] Failed to prepare audio header + data packet: " + e);
					}
				}
			} catch (IOException e1) {
				Log.e(TAG, "[STT] Failed to read file.");
				e1.printStackTrace();
			} catch (Exception e) {
				Log.e(TAG, "[STT] Failed somehow...");
				e.printStackTrace();
			}
			
			//re-setup the media recorder object (needed after stop is called)
			RefreshMediaRecorderThread t = new RefreshMediaRecorderThread((recorderCount+(numRecorders-1))%numRecorders); //essentially, rc-1 with wraparound
			t.start();
		}
	}
}
