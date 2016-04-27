from keras.models import Sequential
from keras.layers.core import Dense, Activation
from keras.optimizers import SGD
from keras.optimizers import Adam
from keras.callbacks import Callback
from keras import backend as K
import numpy as np
import sys
import struct

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

	# Invent some data
	#X_data = np.array([[-1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 1.0, -1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, 0.0, 1.0, -3.0, 0.0, -1.0, -1.0, -1.0, -1.0]])
	#X_data = np.append(X_data, [[-1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0, -1.0, -1.0, 1.0, -1.0, 1.0, -1.0, -1.0, 1.0, 0.0, 1.0, 1.0, 1.0, 2.0, -1.0, 0.0, 2.0]], axis=0)
	X_data = np.array([[0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]])
	#X_data = np.array()
	Y_data = np.array([0.5])

	# For now, only record the states with estimated scores.  (Just count  the other states.)
	with open("states.db", "rb") as f:
	    while True:
	        data = f.read(lStructLen)
	        if not data: break
	        (lTerminal, lComplete, lVisits, lScore, lStateSize, lState0, lState1, lState2) = fnStructUnpack(data)
	        assert(lStateSize == 3)
	        #print lTerminal, lComplete, lVisits, lScore, bin(lState0), bin(lState1), bin(lState2)
	        if lTerminal:
	          lNumTerminal += 1
	          if lScore > 99:
	            lNumTerminalWins += 1
	          else:
	            lNumTerminalLosses += 1
	        elif lComplete:
	          lNumComplete += 1
	          if lScore > 99:
	            lNumCompleteWins += 1
	          else:
	            lNumCompleteLosses += 1
	        else:
	          lNumEstimate += 1
	          (X_data, Y_data) = addSample(X_data, Y_data, [lState0, lState1, lState2], lScore)

	print "  Terminal:", lNumTerminal, "of which", lNumTerminalWins, "wins and", lNumTerminalLosses, "losses."
	print "  Complete:", lNumComplete, "of which", lNumCompleteWins, "wins and", lNumCompleteLosses, "losses."
	print "  Estimate:", lNumEstimate

	return (X_data, Y_data)

def addSample(xiXData, xiYData, xiState, xiScore):
	# Expand the state
	assert(xiState[2] <= 127)
	lBits = []
	for ii in range(0, 3):
		for jj in range(0, 7 if ii == 2 else 64):
			lBits.append(float(xiState[ii] & 1))
			xiState[ii] >>= 1
	#print lBits
	xiXData = np.append(xiXData, [lBits], axis=0)
	xiYData = np.append(xiYData, xiScore / 100.0)
	return (xiXData, xiYData)

print "Reading data..."
(X_data, Y_data) = readDatabase()

print "Compiling network..."
batch_size = 6000
epochs = 100000
nb_epochs = 100

num_props = 64 + 64 + 2 + 4 + 1 # 64 x black pawn, 64 x white pawn, 2 x control props, 4 x goal pseduo-props + 1 x ?

model = Sequential()
model.add(Dense(num_props * 3, input_dim=num_props, init="glorot_uniform", activation="tanh"))
model.add(Dense(1, init="glorot_uniform", activation="sigmoid"))

optimizer = SGD(lr=0.05, momentum=0.97, decay=1e-6, nesterov=False) # This is a very hot start and cooling agressively.  More usually, lr=0.05, decay=1e-6
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
          verbose=2)

np.set_printoptions(threshold=np.nan, precision=1, suppress=True)
errors = abs((np.reshape(model.predict(X_data, batch_size=100, verbose=1), samples) - Y_data) * 100)
#print errors
print "Max error:", errors.max()
print "Mean error:", errors.mean()