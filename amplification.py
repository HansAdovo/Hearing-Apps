import numpy as np
import pyaudio
import pyqtgraph as pg
from pyqtgraph.Qt import QtWidgets, QtCore
import sys
from scipy.signal import butter, lfilter

# Audio stream parameters
FORMAT = pyaudio.paInt16
CHANNELS = 1  # Adjust to channel you want to input from
RATE = 14100
CHUNK = 256  # Increase for more clarity, decrease for lower latency
MAX_PLOT_SIZE = CHUNK * 50

# WDRC parameters for each frequency band to make the audio output more clear
# This is a technique used to make quiet sounds louder and loud sounds softer, making the overall volume more comfortable and easier to hear.
wdrc_params = {
    'low': {'threshold': -40, 'ratio': 3.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'mid': {'threshold': -30, 'ratio': 4.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'high': {'threshold': -20, 'ratio': 5.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10}
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
audio = np.array([], dtype=np.int16)


# Filter functions

# Helper functions for bandpass filter
# This is used to filter out the noise from the audio signal
def bandPassHelper(lowcut, highcut, fs, order=5):
    # Calculate Nyquist frequency
    # nyq is half of the sampling rate since the maximum frequency that can be represented is half of the sampling rate
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    return b, a

# Bandpass filter function
# This function is used to filter out the noise from the audio signal
# It takes the audio signal, lowcut, highcut, sampling frequency, and order as input
def bandPassFilter(data, lowcut, highcut, fs, order=5):
    b, a = bandPassHelper(lowcut, highcut, fs, order=order)
    y = lfilter(b, a, data)
    return y

# This function is used to apply the WDRC to the audio signal
# WDRC stands for Wide Dynamic Range Compression
# This is a technique used to make quiet sounds louder and loud sounds softer, making the overall volume more comfortable and easier to hear.
# The function takes the audio signal, threshold, compression ratio, attack time, release time, gain, and sampling frequency as input
# It returns the audio signal after applying the WDRC
def wdrc(data, threshold, ratio, attack, release, gain, fs):
    # Calculate alpha values for attack and release times
    # Alpha is the smoothing factor for the gain
    # alpha = exp(-log(9) / (fs * time))
    # alphaAttack is the smoothing factor for the gain during attack time
    # alphaRelease is the smoothing factor for the gain during release time
    alphaAttack = np.exp(-np.log(9) / (fs * attack))
    alphaRelease = np.exp(-np.log(9) / (fs * release))
    
    # Convert threshold and gain to linear scale
    # thresholdLinear is the threshold in linear scale
    # gainLinear is the gain in linear scale
    thresholdLinear = 10 ** (threshold / 20)
    gainLinear = 10 ** (gain / 20)
    
    # Initialize gain to 1.0 and output to zeros
    output = np.zeros_like(data)
    gain = 1.0
    
    # Apply WDRC to each sample of the input data
    for i in range(len(data)):
        inputLevel = abs(data[i])
        # Calculate desired gain based on input level, threshold, and compression ratio
        # If input level is below threshold, gain is 1.0
        if inputLevel > thresholdLinear:
            goalGain = (inputLevel / thresholdLinear) ** (1 - ratio)
        # if input level is below threshold, gain is 1.0
        else:
            goalGain = 1.0
        # Apply attack and release times to smooth the gain
        # alpha is the smoothing factor for the gain
        if goalGain < gain:
            alpha = alphaAttack
        else:
            alpha = alphaRelease
        # Smooth the gain by applying alpha
        # Alpha is calculated based on attack and release times
        gain = alpha * gain + (1 - alpha) * goalGain
        output[i] = gainLinear * gain * data[i]
    return output

# This function is used to process the audio signal using WDRC
# It splits the audio signal into frequency bands and applies WDRC to each band separately
# The function takes the audio signal, sampling frequency, and WDRC parameters as input
# It returns the processed audio signal
def processWDRC(data, fs, wdrc_params):
    # Split into frequency bands
    # This is done to apply WDRC to each frequency band separately
    low = bandPassFilter(data, 20, 300, fs) # Adjusted lowcut to 20 to fit within Nyquist frequency
    mid = bandPassFilter(data, 300, 3000, fs) # Adjusted highcut to 3000 to fit within Nyquist frequency
    high = bandPassFilter(data, 3000, 6000, fs) # Adjusted highcut to 6000 to fit within Nyquist frequency
    
    # Apply WDRC to each band separately
    # This is done to make the audio output more clear and comfortable to hear
    # removes noise from the audio signal from each frequency band
    low = wdrc(low, wdrc_params['low']['threshold'], wdrc_params['low']['ratio'], wdrc_params['low']['attack_time'], wdrc_params['low']['release_time'], wdrc_params['low']['gain'], fs)
    mid = wdrc(mid, wdrc_params['mid']['threshold'], wdrc_params['mid']['ratio'], wdrc_params['mid']['attack_time'], wdrc_params['mid']['release_time'], wdrc_params['mid']['gain'], fs)
    high = wdrc(high, wdrc_params['high']['threshold'], wdrc_params['high']['ratio'], wdrc_params['high']['attack_time'], wdrc_params['high']['release_time'], wdrc_params['high']['gain'], fs)
    
    # Combine bands and return the processed audio
    processed_data = low + mid + high
    return processed_data

# This function is called periodically from the timer to update the plots and also the audio output
def update():
    global stream, audio, output_stream
    
    # Read and process audio data from the microphone
    rawData = stream.read(CHUNK, exception_on_overflow=False)
    dataSample = np.frombuffer(rawData, dtype=np.int16)
    audio = np.append(audio, dataSample)
    
    # Apply the WDRC using the bandpass filter
    dataSample = processWDRC(dataSample, RATE, wdrc_params)
    
    
    # Output audio data
    output_stream.write(dataSample.tobytes())
    
    # Limit plot data
    if len(audio) > MAX_PLOT_SIZE:
        audio = audio[-MAX_PLOT_SIZE:]
    
    # Update time domain plot
    time_curve.setData(audio)
    
    # FFT and update frequency domain plot
    fft_data = np.fft.rfft(dataSample * np.hanning(len(dataSample)))
    power_spectrum = 20 * np.log10(np.abs(fft_data) / len(fft_data))
    fft_curve.setData(np.arange(len(power_spectrum)), power_spectrum)

# Timer to update plots and audio output
timer = QtCore.QTimer()
timer.start(10)
timer.timeout.connect(update)
timer.start(10)  # Interval in milliseconds to update the plot

# Start Qt event loop unless running in interactive mode or using py side
if __name__ == '__main__':
    try:
        if (sys.flags.interactive != 1) or not hasattr(QtCore, 'PYQT_VERSION'):
            QtWidgets.QApplication.instance().exec()
    finally:
        # Cleanup
        stream.stop_stream()
        stream.close()
        audio.terminate()

        output_stream.stop_stream()
        output_stream.close()

        audio.terminate()
