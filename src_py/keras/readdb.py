import struct

struct_fmt = '>??idi3Q'
struct_len = struct.calcsize(struct_fmt)
struct_unpack = struct.Struct(struct_fmt).unpack_from
print struct_len, "bytes per record."

lNumTerminal = 0
lNumComplete = 0
lNumEstimate = 0
lNumTerminalWins   = 0
lNumTerminalLosses = 0
lNumCompleteWins   = 0
lNumCompleteLosses = 0

with open("states.db", "rb") as f:
    while True:
        data = f.read(struct_len)
        if not data: break
        (lTerminal, lComplete, lVisits, lScore, lStateSize, lState0, lState1, lState2) = struct_unpack(data)
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

print "Terminal:", lNumTerminal, "of which", lNumTerminalWins, "wins and", lNumTerminalLosses, "losses."
print "Complete:", lNumComplete, "of which", lNumCompleteWins, "wins and", lNumCompleteLosses, "losses."
print "Estimate:", lNumEstimate
