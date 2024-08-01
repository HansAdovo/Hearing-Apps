import numpy as np
import pyqtgraph as pg
from pyqtgraph.Qt import QtWidgets, QtCore
import sys
from scipy.signal import butter, lfilter
import matplotlib.pyplot as plt

# Parameters for sine wave generation
RATE = 44100
CHUNK = 2048
MAX_PLOT_SIZE = CHUNK * 50
FREQUENCY = 440  # Frequency of the sine wave

# WDRC parameters for each frequency band
wdrc_params = {
    'low': {'threshold': -40, 'ratio': 3.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'mid_low': {'threshold': -35, 'ratio': 3.5, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'mid_high': {'threshold': -30, 'ratio': 4.0, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10},
    'high': {'threshold': -25, 'ratio': 4.5, 'attack_time': 0.01, 'release_time': 0.1, 'gain': 10}
}

# Create the Qt Application and the Pyqtgraph window
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
win.setWindowTitle("Sine Wave Audio Data")

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

def wdrc(data, threshold, ratio, attack, release, gain, fs):
    alphaAttack = np.exp(-np.log(9) / (fs * attack))
    alphaRelease = np.exp(-np.log(9) / (fs * release))

    thresholdLinear = 10 ** (threshold / 20)
    gainLinear = 10 ** (gain / 20)
    
    output = np.zeros_like(data)
    gain = 1.0
    
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

def processWDRC(data, fs, wdrc_params):
    low = bandPassFilter(data, 20, 300, fs)
    mid_low = bandPassFilter(data, 300, 1000, fs)
    mid_high = bandPassFilter(data, 1000, 3000, fs)
    high = bandPassFilter(data, 3000, 6000, fs)
    
    low = wdrc(low, wdrc_params['low']['threshold'], wdrc_params['low']['ratio'], wdrc_params['low']['attack_time'], wdrc_params['low']['release_time'], wdrc_params['low']['gain'], fs)
    mid_low = wdrc(mid_low, wdrc_params['mid_low']['threshold'], wdrc_params['mid_low']['ratio'], wdrc_params['mid_low']['attack_time'], wdrc_params['mid_low']['release_time'], wdrc_params['mid_low']['gain'], fs)
    mid_high = wdrc(mid_high, wdrc_params['mid_high']['threshold'], wdrc_params['mid_high']['ratio'], wdrc_params['mid_high']['attack_time'], wdrc_params['mid_high']['release_time'], wdrc_params['mid_high']['gain'], fs)
    high = wdrc(high, wdrc_params['high']['threshold'], wdrc_params['high']['ratio'], wdrc_params['high']['attack_time'], wdrc_params['high']['release_time'], wdrc_params['high']['gain'], fs)
    
    processed_data = low + mid_low + mid_high + high

    return processed_data

def generate_sine_wave(freq, rate, chunk):
    t = np.linspace(0, chunk / rate, chunk, endpoint=False)
    sine_wave = 0.5 * 32767 * np.sin(2 * np.pi * freq * t)
    
    if np.random.rand() < 0.05:  # Add random spikes 5% of the time
        sine_wave += 0.5 * 32767 * np.random.rand(chunk)

    return sine_wave.astype(np.int16)

# This function is called periodically from the timer to update the plots and also the audio output
def update():
    global audio
    
    dataSample = generate_sine_wave(FREQUENCY, RATE, CHUNK)
    audio = np.append(audio, dataSample)
    
    dataSample = processWDRC(dataSample, RATE, wdrc_params)
    
    if len(audio) > MAX_PLOT_SIZE:
        audio = audio[-MAX_PLOT_SIZE:]
    
    time_curve.setData(audio)
    
    fft_data = np.fft.rfft(dataSample * np.hanning(len(dataSample)))
    power_spectrum = 20 * np.log10(np.abs(fft_data) / len(fft_data))
    fft_curve.setData(np.arange(len(power_spectrum)), power_spectrum)

timer = QtCore.QTimer()
timer.timeout.connect(update)
timer.start(10)

if __name__ == '__main__':
    try:
        if (sys.flags.interactive != 1) or not hasattr(QtCore, 'PYQT_VERSION'):
            QtWidgets.QApplication.instance().exec()
    finally:
        pass  # No real-time audio to clean up
