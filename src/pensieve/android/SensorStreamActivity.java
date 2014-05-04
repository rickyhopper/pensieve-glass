package pensieve.android;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.commons.io.FileUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.zeromq.ZMQ;

import android.app.Activity;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Environment;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

public class SensorStreamActivity extends Activity implements TextReceivedListener {
	public static final String TAG = "SensorStream";

	private boolean silent = true; // suppress all audio output
	private TextToSpeech tts = null;
	private boolean ttsReady = false;
	
	private byte imageHeaderDataSep = '\n'; // used to separate image header from data in combined packet mode; must be byte or byte[]

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
	private NewTimerTask timerTask = new NewTimerTask(this) {
		public void run() {
			PostAudioSnippetThread audioThread = new PostAudioSnippetThread(this.activity);
			audioThread.start();
		}
	};

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_sensor_stream);
		
		// Keep screen on
		Window myWindow = getWindow();
		myWindow.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
		myWindow.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
		myWindow.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
		
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
				Log.e("log", "Recorder setup NOPEd everywhere ..." + e);
			}
		}
		
		rec[recorderCount].start();
		
		//schedule audio timer for every half second
		audioTimer.scheduleAtFixedRate(timerTask, 2000, 2000); //can adjust for testing

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
		
	}

	@Override
	protected void onPause() {
		if(ttsReady) tts.speak("Paused", TextToSpeech.QUEUE_FLUSH, null);
		
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

	private String getAudioPath(int num) {
		return baseAudioPath + num + ".m4a";
	}
	
	private void appendTextToView(String str) {
		TextView txtView = (TextView) findViewById(R.id.mainViewText);
		String text = txtView.getText().toString();
		text += " " + str;
		while (text.length() > 100) {
			text = text.substring(text.indexOf(" ") + 1);
		}
		txtView.setText(text);
	}
	
	abstract class NewTimerTask extends TimerTask {
		TextReceivedListener activity;
		
		public NewTimerTask(TextReceivedListener l) {
			super();
			activity = l;
		}
		
	}
	
	class RefreshMediaRecorderThread extends Thread {
		int recNum;
		
		public RefreshMediaRecorderThread(int num) {
			recNum = num;
		}
		
		public void run() {
			rec[recNum].reset();
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
		    Log.d("log", "Reset audio recorder");
			
			try {
				rec[recNum].prepare();
				Log.d("log", "prepared audio recorder");
			} catch (IOException e) {
				Log.e("log", "oh god we're going down ...\n" + e);
			}
		}
	}
	
	class PostAudioSnippetThread extends Thread {
		private TextReceivedListener activity;
		
		public PostAudioSnippetThread(TextReceivedListener l) {
			activity = l;
		}
		
		public void run() {
			Log.d(TAG, "running PostAudioSnippetThread - " + recorderCount);
			
			//stop recording
			rec[recorderCount].stop();
			
			//open the audio file to send to the server
			File f = new File(getAudioPath(recorderCount));
			byte[] bytes;
			recorderCount = (recorderCount+1) % numRecorders;
			rec[recorderCount].start();
			
			//re-setup the media recorder object (needed after stop is called)
			RefreshMediaRecorderThread t = new RefreshMediaRecorderThread((recorderCount+(numRecorders-1))%numRecorders); //essentially, recorderCount-1 with wraparound
			t.start();
			
			try {
				bytes = FileUtils.readFileToByteArray(f);
				// Send over ZMQ (header as JSON-encoded string and raw image data concatenated with a separator)
				// * TODO Check if ZMQ socket is ready to send
				synchronized(socketLock) {
					try {
						// * Prepare header request
						JSONObject requestObj = new JSONObject();
						requestObj.put("type", "stt"); //speech to text
						requestObj.put("num_bytes", bytes.length);
						requestObj.put("sample_rate", sampleRate);
						requestObj.put("format", audioFormat); //unhardcode
						String request = requestObj.toString();
						Log.d(TAG, "[STT] Request: " + request);
						
						// * Send header request and data concatenated together with a known separator
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
									try {
										JSONArray result = replyObj.getJSONArray("result");
										Log.v(TAG, "[STT] Got result object - " + result);
										JSONObject a = result.getJSONObject(0);
										JSONArray alternative = a.getJSONArray("alternative");
										Log.v(TAG, "[STT] Got alternative object");
										Log.v(TAG, "[STT] " + alternative);
										JSONObject entry = alternative.getJSONObject(0);
										Log.v(TAG, "[STT] Got entry object");
										String transcript = entry.getString("transcript");
										Log.v(TAG, "[STT] Adding text '" + transcript + "'.");
										activity.onTextReceived(transcript);
									} catch (JSONException e) {
										int status = replyObj.getInt("status");
										if (status == 200) {
											Log.d(TAG, "[STT] Reply status OK: " + status);
										} else {
											Log.w(TAG, "[STT] Reply status not favorable: " + status);
										}
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
		}
	}

	@Override
	public void onTextReceived(String str) {
		final String s = str;
		runOnUiThread(new Runnable() {
			@Override
			public void run() {
				appendTextToView(s);
			}
		});
	}
}
