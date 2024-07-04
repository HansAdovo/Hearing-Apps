import numpy as np
import pyaudio
import pyqtgraph as pg
from pyqtgraph.Qt import QtWidgets, QtCore
import sys
from scipy.signal import butter, lfilter

# Audio stream parameters
FORMAT = pyaudio.paInt16
CHANNELS = 1
RATE = 44100
CHUNK = 256
MAX_PLOT_SIZE = CHUNK * 50

# WDRC parameters for each frequency band to make the audio output more clear
# This is a technique used to make quiet sounds louder and loud sounds softer, making the overall volume more comfortable and easier to hear.
wdrc_params = {'threshold': -30, 'ratio': 3.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10}

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
    data_plot.setYRange(-8000,8000)
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

def bandPassHelper(lowcut, highcut, fs, order=5):
    nyq = 0.5 * fs
    low = lowcut / nyq
    high = highcut / nyq
    b, a = butter(order, [low, high], btype='band')
    return b, a

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

# This function is called periodically from the timer to update the plots and also the audio output
def update():
    global stream, audio, output_stream
    
    # Read and process audio data from the microphone
    rawData = stream.read(CHUNK, exception_on_overflow=False)
    dataSample = np.frombuffer(rawData, dtype=np.int16)
    audio = np.append(audio, dataSample)
    
    # Apply butter-worth bandpass filter to the audio data
    dataSample = bandPassFilter(dataSample, 20, 6000, RATE, order=5)

    # Apply the WDRC
    #dataSample = wdrc(dataSample, wdrc_params['threshold'], wdrc_params['ratio'], wdrc_params['attack_time'], wdrc_params['release_time'], wdrc_params['gain'], RATE)

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
