# Changelog

## parley-files-sftp-jsch 1.0.0 (2026-10-21)

The first release. The JSch provider for parley-files-sftp. It was part of parley-files-sftp (package
`us.bringardner.parley.files.sftp.client.jsch`, before that BjlFileSystemSftp's
`us.bringardner.io.filesource.sftp.client.jsch`) and is now a project of its own, found on the class path
through `ServiceLoader`.

- Package: `us.bringardner.parley.files.sftp.jsch`.
- Module name (`Automatic-Module-Name`): `us.bringardner.parley.files.sftp.jsch`.
- **Writing at a position throws** an `IOException` at the write: JSch has no call for it, and the
  emulation that was used could put the data in the wrong place if the file changed size meanwhile.
- Tested with parley-files-sftp's provider test suite and its own tests.
