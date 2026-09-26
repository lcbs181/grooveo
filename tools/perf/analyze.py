"""Summarize a Grooveo Perfetto trace: CPU per thread, frame times, the slices
that make long frames long, and Grooveo's own trace sections.
Usage: analyze.py <trace>   (needs `pip install perfetto`, see trace.sh)"""
import sys
from perfetto.trace_processor import TraceProcessor

PKG = "dev.schlubbe.musicagent.standalone"
tp = TraceProcessor(trace=sys.argv[1])

def q(sql):
    return list(tp.query(sql))

upid = q(f"select upid from process where name = '{PKG}' limit 1")
if not upid:
    sys.exit("app process not in trace")
upid = upid[0].upid
span = q("select (end_ts - start_ts) / 1e9 as s from trace_bounds")[0].s

print(f"\n== CPU time per thread ({span:.1f}s trace) ==")
for r in q(f"""
    select thread.name as t, sum(dur) / 1e6 as ms
    from sched join thread using (utid)
    where thread.upid = {upid} group by utid order by ms desc limit 12"""):
    print(f"  {r.t or '?':<28} {r.ms:8.0f} ms  {r.ms / 10 / span:5.1f}% of a core")

print("\n== Frames (main thread Choreographer#doFrame) ==")
frames = q(f"""
    select s.ts, s.dur from slice s join thread_track tt on s.track_id = tt.id
    join thread using (utid)
    where thread.upid = {upid} and thread.is_main_thread and s.name like 'Choreographer#doFrame%'""")
if frames:
    durs = sorted(f.dur / 1e6 for f in frames)
    pct = lambda p: durs[min(len(durs) - 1, int(len(durs) * p))]
    print(f"  {len(durs)} frames, {len(durs) / span:.0f}/s, p50 {pct(.5):.1f} ms, p90 {pct(.9):.1f} ms, max {durs[-1]:.1f} ms")
    print(f"  over 16 ms: {sum(d > 16 for d in durs)}, over 50 ms: {sum(d > 50 for d in durs)}")

print("\n== Heaviest slices inside long (>16 ms) main-thread frames ==")
for r in q(f"""
    with f as (select s.id, s.ts, s.dur, s.track_id from slice s join thread_track tt on s.track_id = tt.id
               join thread using (utid)
               where thread.upid = {upid} and thread.is_main_thread
                 and s.name like 'Choreographer#doFrame%' and s.dur > 16e6)
    select c.name as n, count(*) as cnt, sum(c.dur) / 1e6 as ms, max(c.dur) / 1e6 as mx
    from slice c join f on c.track_id = f.track_id and c.ts >= f.ts and c.ts < f.ts + f.dur and c.depth > 0
    group by c.name order by ms desc limit 15"""):
    print(f"  {r.n[:60]:<60} x{r.cnt:<4} {r.ms:7.1f} ms total, max {r.mx:6.1f}")

print("\n== Grooveo trace sections and composables (all threads) ==")
for r in q(f"""
    select s.name as n, count(*) as cnt, sum(s.dur) / 1e6 as ms, max(s.dur) / 1e6 as mx
    from slice s join thread_track tt on s.track_id = tt.id join thread using (utid)
    where thread.upid = {upid}
      and (s.name glob '*PlayerController*' or s.name glob '*Crossfade*' or s.name glob '*Reverb*'
           or s.name glob '*TrackAnalyzer*' or s.name glob '*schlubbe*' or s.name glob '*Screen*'
           or s.name glob '*Visualizer*')
    group by s.name order by ms desc limit 20"""):
    print(f"  {r.n[:60]:<60} x{r.cnt:<5} {r.ms:8.1f} ms total, max {r.mx:6.1f}")
