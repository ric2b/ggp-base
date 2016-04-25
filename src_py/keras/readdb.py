import struct

struct_fmt = '>??idi3Q'
struct_len = struct.calcsize(struct_fmt)
struct_unpack = struct.Struct(struct_fmt).unpack_from

print struct_len

num_terminal = 0
num_complete = 0
num_estimate = 0

results = []
with open("states.db", "rb") as f:
    while True:
        data = f.read(struct_len)
        if not data: break
        s = struct_unpack(data)
        if s[0]:
          num_terminal += 1
        elif s[1]:
          num_complete += 1
        else:
          num_estimate += 1
        results.append(s)

print "Terminal: ", num_terminal, ", Complete: ", num_complete, ", Estimate: ", num_estimate
