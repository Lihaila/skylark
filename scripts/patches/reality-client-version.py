# Reality servers can require a minimum client version (Xray's minClientVer).
# Stock sing-box reports 1.8.1, which such servers silently reject
# ("reality verification failed"). Report a current Xray version instead.
import pathlib, sys
p = pathlib.Path(sys.argv[1]) / "common/tls/reality_client.go"
s = p.read_text()
old = "hello.SessionId[0] = 1\n\thello.SessionId[1] = 8\n\thello.SessionId[2] = 1"
new = "hello.SessionId[0] = 26\n\thello.SessionId[1] = 1\n\thello.SessionId[2] = 18"
if new in s:
    print("reality patch: version already applied")
elif old in s:
    s = s.replace(old, new); print("reality patch: version applied")
else:
    sys.exit("reality patch: version target not found - check sing-box version")

# The timestamp the server checks (bytes 4-8) used the raw system clock, ignoring
# the NTP-corrected time. Phones with a drifted clock then fail verification.
old_t = "binary.BigEndian.PutUint32(hello.SessionId[4:], uint32(time.Now().Unix()))"
new_t = "binary.BigEndian.PutUint32(hello.SessionId[4:], uint32(nowTime.Unix()))"
if new_t in s:
    print("reality patch: time already applied")
elif old_t in s:
    s = s.replace(old_t, new_t); print("reality patch: time applied")
else:
    sys.exit("reality patch: time target not found - check sing-box version")
p.write_text(s)
