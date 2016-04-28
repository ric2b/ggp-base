from keras.models import Sequential
from keras.layers.core import Dense, Activation
from keras.optimizers import SGD
from keras.optimizers import Adam
from keras.callbacks import Callback
from keras import backend as K
import numpy as np
import sys
import struct
import random

gNumPseudoProps = 4 + 1 # 4 x goal + 1 x terminal?
gNumProps = 64 + 64 + 2 # 64 x black pawn, 64 x white pawn, 2 x control props
gNumSamples = 0

def readDatabase(filename="states.db"):
	lStructFormat = '>??idi3Q'
	lStructLen = struct.calcsize(lStructFormat)
	fnStructUnpack = struct.Struct(lStructFormat).unpack_from
	print " ", lStructLen, "bytes per record."

	lNumTerminal = 0
	lNumComplete = 0
	lNumEstimate = 0
	lNumTerminalWins   = 0
	lNumTerminalLosses = 0
	lNumCompleteWins   = 0
	lNumCompleteLosses = 0

	# Pre-allocate arrays to hold the data
	X_data = np.zeros((30000, gNumProps))
	Y_data = np.zeros((30000,))

	# Read the states.  Use some of them and discard others.
	# Aim for approximatley equals numbers of terminal/complete/estimated states.
	# Aim for approximately equals numbers of wins and losses (for terminal & complete states).
	with open("states.db", "rb") as f:
		while True:
			data = f.read(lStructLen)
			if not data: break
			(lTerminal, lComplete, lVisits, lScore, lStateSize, lState0, lState1, lState2) = fnStructUnpack(data)
			assert(lStateSize == 3)
			if lTerminal:
				lNumTerminal += 1
				if lScore > 99:
					lNumTerminalWins += 1
					addSample(500, X_data, Y_data, [lState0, lState1, lState2], lScore)
			 	else:
					lNumTerminalLosses += 1
					addSample(170, X_data, Y_data, [lState0, lState1, lState2], lScore)
			elif lComplete:
				lNumComplete += 1
				if lScore > 99:
					lNumCompleteWins += 1
					addSample(250, X_data, Y_data, [lState0, lState1, lState2], lScore)
				else:
					lNumCompleteLosses += 1
					addSample(90, X_data, Y_data, [lState0, lState1, lState2], lScore)
			else:
				lNumEstimate += 1
				addSample(1, X_data, Y_data, [lState0, lState1, lState2], lScore)

	print "  Terminal:", lNumTerminal, "of which", lNumTerminalWins, "wins and", lNumTerminalLosses, "losses."
	print "  Complete:", lNumComplete, "of which", lNumCompleteWins, "wins and", lNumCompleteLosses, "losses."
	print "  Estimate:", lNumEstimate

	print "  Samples: ", gNumSamples
	X_data = np.resize(X_data, (gNumSamples, gNumProps))
	Y_data = np.resize(Y_data, (gNumSamples,))
	return (X_data, Y_data)

def addSample(xiOdds, xiXData, xiYData, xiState, xiScore):

	if random.random() > 1.0 / xiOdds:
		return

	global gNumSamples

	# Expand the state
	assert(xiState[2] <= 127)
	lBits = []
	for ii in range(0, 3):				
		for jj in range(gNumPseudoProps if ii == 0 else 0, # The first long of packed bits begin with the pseudo-props added by Sancho.  Skip those.
			            7 if ii == 2 else 64):             # Only the last 7 bits of the final long are valid.  Stop at that point.
			lBits.append(float(xiState[ii] & 1))
			xiState[ii] >>= 1
	#print lBits
	xiXData[gNumSamples] = lBits
	xiYData[gNumSamples] = xiScore / 100.0
	gNumSamples += 1

print "Reading data..."
(X_data, Y_data) = readDatabase()
values = Y_data * 100;
print "Min value: ", values.min()
print "Max value: ", values.max()
print "Mean value:", values.mean()
print "Std. Dev.:  ", values.std()

print "Compiling network..."
nb_epochs = 1000

model = Sequential()
model.add(Dense(20, input_dim=gNumProps, init="glorot_uniform", activation="tanh"))
model.add(Dense(1, init="glorot_uniform", activation="sigmoid"))

optimizer = SGD(lr=0.8, momentum=0.97, decay=1e-8, nesterov=False) # "Standard" parameters would be lr=0.05, decay=1e-6
# optimizer = Adam(lr=0.01, beta_1=0.9, beta_2=0.999, epsilon=1e-08)
model.compile(loss='mean_squared_error', optimizer=optimizer)
print optimizer.get_config()

X_train = np.copy(X_data)
Y_train = np.copy(Y_data)
samples = Y_train.size

print "Training network..."
model.fit(X_train, Y_train,
		  batch_size=samples, 
		  nb_epoch=nb_epochs,
		  validation_split=0.2,
		  verbose=2)

np.set_printoptions(threshold=np.nan, precision=1, suppress=True)
errors = abs((np.reshape(model.predict(X_data, batch_size=100, verbose=1), samples) - Y_data) * 100)
#print errors
print "Max error: ", errors.max()
print "Mean error:", errors.mean()
print "Std. Dev.: ", errors.std()
