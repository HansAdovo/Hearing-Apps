import numpy as np
import pyaudio
import pyqtgraph as pg
from pyqtgraph.Qt import QtWidgets, QtCore
import sys
from scipy.signal import butter, lfilter
import wave
import matplotlib.pyplot as plt

# Audio stream parameters
FORMAT = pyaudio.paInt16
CHANNELS = 1
RATE = 44100
CHUNK = 2048
MAX_PLOT_SIZE = CHUNK * 50

# WDRC parameters for each frequency band to make the audio output more clear
# This is a technique used to make quiet sounds louder and loud sounds softer, making the overall volume more comfortable and easier to hear.
wdrc_params = {
    'low': {'threshold': -40, 'ratio': 3.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'mid_low': {'threshold': -35, 'ratio': 3.5, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'mid_high': {'threshold': -30, 'ratio': 4.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'high': {'threshold': -25, 'ratio': 4.5, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10}
}

# Initialize PyAudio
audio = pyaudio.PyAudio()
stream = audio.open(format=FORMAT, channels=CHANNELS, rate=RATE, input=True, frames_per_buffer=CHUNK)

# Output stream to play the audio
# Currently goes to speakers
output_stream = audio.open(format=FORMAT, channels=CHANNELS, rate=RATE, output=True, frames_per_buffer=CHUNK)

# create the Qt Application and the Pyqtgraph window
def plots(window):
    # Time Domain Plot
    data_plot = window.addPlot(title="Audio Signal Vs Time")
    data_plot.setXRange(0, MAX_PLOT_SIZE)
    data_plot.setYRange(-8000, 8000)
    data_plot.showGrid(True, True)
    data_plot.addLegend()
    time_curve = data_plot.plot(pen=(24, 215, 248), name="Time Domain Audio")
    
    # Frequency Domain Plot
    window.nextRow()
    fft_plot = window.addPlot(title="Power Vs Frequency Domain")
    fft_plot.addLegend()
    fft_curve = fft_plot.plot(pen='y', name="Power Spectrum")
    fft_plot.showGrid(True, True)

    return time_curve, fft_curve

# Ensure a QApplication instance is created
app = QtWidgets.QApplication([])

# Create the main window
win = pg.GraphicsLayoutWidget(show=True)
win.setWindowTitle("Microphone Audio Data")

time_curve, fft_curve = plots(win)

# Buffer for storing audio data
audio_buffer = np.array([], dtype=np.int16)
processed_audio_buffer = np.array([], dtype=np.int16)

# 5th order Butterworth bandpass filter to filter out noise
# This is the helper function to create the filter
def bandPassHelper(lowcut, highcut, fs, order=5):
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    return b, a

# This is the main function to apply the bandpass butterworth filter
def bandPassFilter(data, lowcut, highcut, fs, order=5):
    b, a = bandPassHelper(lowcut, highcut, fs, order=order)
    y = lfilter(b, a, data)
    return y

# WDRC (Wide Dynamic Range Compression) algorithm to make the audio output more clear
# This is a technique used to make quiet sounds louder and loud sounds softer, making the overall volume more comfortable and easier to hear.
# This is the main function to apply the WDRC algorithm
def wdrc(data, threshold, ratio, attack, release, gain, fs):
    # Convert attack and release times to alpha values
    alphaAttack = np.exp(-np.log(9) / (fs * attack))
    alphaRelease = np.exp(-np.log(9) / (fs * release))

    # Convert threshold and gain to linear scale
    thresholdLinear = 10 ** (threshold / 20)
    gainLinear = 10 ** (gain / 20)
    
    # Initialize gain to 1.0 and output to zeros
    output = np.zeros_like(data)
    gain = 1.0
    
    # Apply WDRC to each sample of the input data
    # The WDRC algorithm is applied to each sample of the input data to adjust the gain based on the input level and the desired gain.
    for i in range(len(data)):
        inputLevel = abs(data[i])

        if inputLevel > thresholdLinear:
            goalGain = (inputLevel / thresholdLinear) ** (1 - ratio)
        else:
            goalGain = 1.0

        if goalGain < gain:
            alpha = alphaAttack
        else:
            alpha = alphaRelease

        gain = alpha * gain + (1 - alpha) * goalGain
        output[i] = gainLinear * gain * data[i]
    return output

# This function is called to process the audio data using the WDRC algorithm and butterworth bandpass filter
# The audio data is split into frequency bands and the WDRC algorithm is applied to each band separately.
# The processed audio data is then combined and returned.
def processWDRC(data, fs, wdrc_params):
    # Apply bandpass butterworth filter to split audio frequency bands
    low = bandPassFilter(data, 20, 300, fs)
    mid_low = bandPassFilter(data, 300, 1000, fs)
    mid_high = bandPassFilter(data, 1000, 3000, fs)
    high = bandPassFilter(data, 3000, 6000, fs)
    
    # Apply WDRC to each band separately
    low = wdrc(low, wdrc_params['low']['threshold'], wdrc_params['low']['ratio'], wdrc_params['low']['attack_time'], wdrc_params['low']['release_time'], wdrc_params['low']['gain'], fs)
    mid_low = wdrc(mid_low, wdrc_params['mid_low']['threshold'], wdrc_params['mid_low']['ratio'], wdrc_params['mid_low']['attack_time'], wdrc_params['mid_low']['release_time'], wdrc_params['mid_low']['gain'], fs)
    mid_high = wdrc(mid_high, wdrc_params['mid_high']['threshold'], wdrc_params['mid_high']['ratio'], wdrc_params['mid_high']['attack_time'], wdrc_params['mid_high']['release_time'], wdrc_params['mid_high']['gain'], fs)
    high = wdrc(high, wdrc_params['high']['threshold'], wdrc_params['high']['ratio'], wdrc_params['high']['attack_time'], wdrc_params['high']['release_time'], wdrc_params['high']['gain'], fs)
    
    # Combine bands and return the processed audio
    processed_data = low + mid_low + mid_high + high
    return processed_data

# This function is called periodically from the timer to update the plots and also the audio output
# The function reads audio data from the microphone, processes it using the WDRC algorithm, and then outputs the processed audio.
# Real-time audio data is displayed in the time domain plot and the frequency domain plot.
def update():
    global stream, audio_buffer, processed_audio_buffer, output_stream
    
    # Read and process audio data from the microphone
    rawData = stream.read(CHUNK, exception_on_overflow=False)
    dataSample = np.frombuffer(rawData, dtype=np.int16)
    audio_buffer = np.append(audio_buffer, dataSample)
    
    # Process audio data using WDRC algorithm and butterworth bandpass filter
    dataSample = processWDRC(dataSample, RATE, wdrc_params)
    
    # Append processed audio data to the buffer
    processed_audio_buffer = np.append(processed_audio_buffer, dataSample)

    # Output audio data
    output_stream.write(dataSample.tobytes())
    
    # Limit plot data
    if len(audio_buffer) > MAX_PLOT_SIZE:
        audio_buffer = audio_buffer[-MAX_PLOT_SIZE:]
    
    # Update time domain plot
    time_curve.setData(audio_buffer)
    
    # FFT and update frequency domain plot
    fft_data = np.fft.rfft(dataSample * np.hanning(len(dataSample)))
    power_spectrum = 20 * np.log10(np.abs(fft_data) / len(fft_data))
    fft_curve.setData(np.arange(len(power_spectrum)), power_spectrum)

# Timer to update plots and audio output
timer = QtCore.QTimer()
timer.start(10)
timer.timeout.connect(update)
timer.start(10)  # Interval in milliseconds to update the plot

# Function to save audio data to a file after the application is closed
def save_audio(filename, audio_data, rate):
    audio_data = np.asarray(audio_data, dtype=np.int16)
    
    with wave.open(filename, 'wb') as wf:
        wf.setnchannels(CHANNELS)
        wf.setsampwidth(audio.get_sample_size(FORMAT))
        wf.setframerate(rate)
        wf.writeframes(audio_data.tobytes())

# Start Qt event loop unless running in interactive mode or using py side
# Main function to run the application
if __name__ == '__main__':
    try:
        if (sys.flags.interactive != 1) or not hasattr(QtCore, 'PYQT_VERSION'):
            QtWidgets.QApplication.instance().exec()
    finally:
        # Save processed audio to file
        save_audio("processed_audio.wav", processed_audio_buffer, RATE)
        
        # Cleanup
        stream.stop_stream()
        stream.close()
        audio.terminate()
        output_stream.stop_stream()
        output_stream.close()
        audio.terminate()
