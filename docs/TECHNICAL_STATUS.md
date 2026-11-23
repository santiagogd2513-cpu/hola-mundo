# Technical status

The supplied archive contains LibertyV.exe and Windows DLLs including xcompress.dll and lzx.dll, plus two 32-byte key files. The executable references RPF7 parsing, XMem/LZX compression, AWC audio operations, and platform key checks.

Windows DLLs cannot be loaded directly by Android. The Android implementation must use managed code or native libraries built for Android ABIs. Keys remain local and must never be committed.

Current code implements RPF7 header parsing, single-pass AES TOC decryption, entry-tree validation, navigation, and raw data export. XMem/LZX decoding/encoding, AWC processing, and archive rebuilding are not implemented. Xbox-ready output is not claimed.

Always test with copies. The app opens a cache copy and never overwrites the selected original archive.
