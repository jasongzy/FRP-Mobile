import os
import struct
import sys
import zipfile

MACHINES = {"arm64-v8a": 183, "armeabi-v7a": 40, "x86_64": 62}
CORES = ("frpc", "frps", "frpmobile")


def verify_elf(header: bytes, machine: int):
    assert header[:4] == b"\x7fELF" and header[5] == 1, "Expected a little-endian ELF binary"
    assert struct.unpack_from("<HH", header, 16) == (3, machine), "Incorrect PIE type or architecture"
    assert header[4] == (1 if machine == 40 else 2), "Incorrect ELF class"
    if header[4] == 2:
        offset = struct.unpack_from("<Q", header, 32)[0]
        size, count = struct.unpack_from("<HH", header, 54)
        for index in range(count):
            entry = offset + index * size
            if struct.unpack_from("<I", header, entry)[0] == 1:
                alignment = struct.unpack_from("<Q", header, entry + 48)[0]
                assert alignment >= 16384, "64-bit binaries must support 16 KiB page sizes"


if __name__ == "__main__":
    target = sys.argv[1]
    if os.path.isdir(target):
        for abi, machine in MACHINES.items():
            for core in CORES:
                path = os.path.join(target, abi, f"lib{core}.so")
                with open(path, "rb") as file:
                    verify_elf(file.read(4096), machine)
                assert os.path.getsize(path) > 500_000, f"Unexpectedly small binary: {path}"
    else:
        with zipfile.ZipFile(target) as apk:
            for abi, machine in MACHINES.items():
                for core in CORES:
                    name = f"lib/{abi}/lib{core}.so"
                    with apk.open(name) as file:
                        verify_elf(file.read(4096), machine)
                    assert apk.getinfo(name).file_size > 500_000, f"Unexpectedly small binary: {name}"
    print("Verified frpc, frps and supervisor for all three ABIs")
