"""Verify a pulled device render. Never treats an APK build as video evidence.

Usage: python tools/verify_render.py input.mp4 output.mp4 --start-ms 250 --end-ms 1750
"""
import argparse
import json
import subprocess
from pathlib import Path


def run(*args):
    return subprocess.run(args, check=True, capture_output=True).stdout


def probe(path):
    return json.loads(run("ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json", str(path)))


def packets(path, stream):
    return json.loads(run("ffprobe", "-v", "error", "-select_streams", stream,
                          "-show_packets", "-show_data_hash", "sha256", "-of", "json", str(path)))["packets"]


def verify(source, output, start_ms, end_ms):
    before, after = probe(source), probe(output)
    vin = next(s for s in before["streams"] if s["codec_type"] == "video")
    vout = next(s for s in after["streams"] if s["codec_type"] == "video")
    assert (vin["width"], vin["height"]) == (vout["width"], vout["height"]), "Resolution changed"
    run("ffmpeg", "-v", "error", "-xerror", "-i", str(output), "-f", "null", "-")
    start, end = start_ms / 1000, end_ms / 1000
    expected = [p for p in packets(source, "v:0") if start <= float(p["pts_time"]) < end]
    encoded = packets(output, "v:0")
    source_times = sorted(float(p["pts_time"]) - start for p in expected)
    earlier = [p for p in packets(source, "v:0") if float(p["pts_time"]) < start]
    if earlier and (not source_times or source_times[0] > .000001):
        source_times.insert(0, 0.0)  # The source image held at a non-frame-aligned trim boundary.
    assert len(source_times) == len(encoded), f"Frame count {len(encoded)} != {len(source_times)}"
    output_times = sorted(float(p["pts_time"]) for p in encoded)
    assert all(abs(a - b) < .002 for a, b in zip(source_times, output_times)), "Frame timestamps changed"
    assert abs(float(vout["duration"]) - (end - start)) < .002, "Video endpoint changed"
    def rotation(stream):
        return next((d["rotation"] for d in stream.get("side_data_list", []) if "rotation" in d), 0)
    assert rotation(vin) == rotation(vout), "Rotation changed"
    hashes = run("ffmpeg", "-v", "error", "-i", str(output), "-map", "0:v:0", "-f", "framemd5", "-").decode()
    unique = {line.split(",")[-1].strip() for line in hashes.splitlines() if not line.startswith("#")}
    assert len(unique) > 1, "Video is frozen (this gate requires a moving fixture)"
    audio_in = [s for s in before["streams"] if s["codec_type"] == "audio"]
    audio_out = [s for s in after["streams"] if s["codec_type"] == "audio"]
    wanted = [p for p in packets(source, "a:0") if start <= float(p["pts_time"]) < end] if audio_in else []
    assert bool(wanted) == bool(audio_out), "Selected audio track lost or added"
    audio_count = 0
    if wanted:
        copied = packets(output, "a:0")
        assert [p["data_hash"] for p in wanted] == [p["data_hash"] for p in copied], "AAC data changed"
        assert all(abs(float(a["pts_time"]) - start - float(b["pts_time"])) < .002
                   for a, b in zip(wanted, copied)), "Audio timestamps changed"
        audio_count = len(copied)
    return {"source": str(source), "output": str(output), "frames": len(encoded),
            "distinct_decoded_frames": len(unique), "audio_packets": audio_count,
            "resolution": [vout["width"], vout["height"]], "full_decode": "passed",
            "timestamp_tolerance_ms": 2, "audio_bitstream_preserved": bool(wanted),
            "video_duration_seconds": float(vout["duration"]), "rotation_degrees": rotation(vout)}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--start-ms", type=int, required=True)
    parser.add_argument("--end-ms", type=int, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.source, args.output, args.start_ms, args.end_ms), indent=2))
