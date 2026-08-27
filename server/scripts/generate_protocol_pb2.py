"""Generate canonical protocol protobuf classes.

Run from ``server/`` with::

    uv run --group dev python scripts/generate_protocol_pb2.py

The command intentionally requests only Python message classes; it does not
generate gRPC services or copy/redefine the canonical schema.
"""

from pathlib import Path

from grpc_tools import protoc


def main() -> None:
    server_root = Path(__file__).resolve().parents[1]
    proto_root = server_root.parent / "protocol" / "proto"
    proto_file = "remanence/protocol/v1/remanence_v1.proto"
    result = protoc.main(
        (
            "protoc",
            f"--proto_path={proto_root}",
            f"--python_out={server_root / 'src'}",
            proto_file,
        )
    )
    if result != 0:
        raise SystemExit(result)


if __name__ == "__main__":
    main()
