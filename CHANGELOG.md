# Changelog

## parley-files-sftp-jsch 2.0.0 (unreleased)

### Changed (needs a configuration change)

- The provider is chosen with `parley.files.sftp.implementation=jsch` (was `parley.sftp.implementation`),
  and the shared suite's settings are `parley.files.sftp.test.*`.

## parley-files-sftp-jsch 1.1.0 (unreleased)

### Changed (no code change needed)

- `createNewFile()` is atomic, as FileSource's contract requires. JSch can't send SFTP's exclusive-create
  flag, so it writes an empty file under a name of its own and gives it the path with a step the server
  refuses if something is there: a hard link (`hardlink@openssh.com`), or a plain SFTP rename on a server
  without `posix-rename@openssh.com`. Only a server with the replacing rename but no hard links still gets
  the old check-then-create.
- `sync()` (FileChannel.force()) only sends what is held: JSch can't send `fsync@openssh.com`.

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
