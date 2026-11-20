# parley-files-sftp-jsch

The [JSch](https://github.com/mwiede/jsch) provider for
[parley-files-sftp](https://github.com/parley-project/parley-files-sftp): SFTP through the maintained JSch fork (`com.github.mwiede:jsch`).

```xml
<dependency>
    <groupId>us.bringardner.parley</groupId>
    <artifactId>parley-files-sftp-jsch</artifactId>
    <version>1.0.0</version>
</dependency>
```

It brings parley-files-sftp with it. On the class path it is used when it is the only provider there, or when it is chosen: the
`implementation` connection property or `-Dparley.files.sftp.implementation=jsch`.

## Limitation: no writing at a position

**JSch can't write at a position in a file.** Its API has no call for it, and the only way to emulate
one (ask the file's size, then append at "position - size") puts the data in the wrong place, silently,
if the file changes size in between. This provider doesn't emulate it. Instead, a write at a position
throws an `IOException` that says so:

- writing to a random access stream (`FileSource.getRandomAccessStream("rw")`, `"rws"`, `"rwd"`, or a
  `SeekableByteChannel` opened for writing) throws at the first write;
- growing a file with `setLength` throws.

Everything else works: reading, writing and appending whole files with streams, listing, links,
permissions, and random access *reading* (mode `"r"`), including at any position. Shrinking a file
with `setLength` works too.

If your application writes at positions, use another provider, such as parley-ssh's
(`parley-files-sftp-parley-ssh`) or Apache MINA SSHD's (`parley-files-sftp-mina`).

## Limitation: can't set a symbolic link's own owner, group or times

On Linux and macOS a symbolic link has its own owner, group and times, and `chown -h` and `touch -h` change
the link rather than its file. Over SFTP that takes OpenSSH's `lsetstat@openssh.com` extension, and
**JSch has no public way to send an extended request**, so this provider can't use it. A plain SETSTAT
always follows the link and would change the file the link leads to, so this provider doesn't send it for a link.
Instead, `setOwner`, `setGroup`, `setLastModifiedTime` and `setLastAccessTime` on a link return `false` and
change nothing, and the link's target is left alone.

Everything that reads a link works: owner, group, times, permission bits and length are the link's own (from
LSTAT), as they are with the other providers. Changing permissions follows the link to its file, as `chmod` does.

If your application sets a link's own owner or times, use another provider, such as parley-ssh's
(`parley-files-sftp-parley-ssh`) or Apache MINA SSHD's (`parley-files-sftp-mina`), against a server that offers
the extension (OpenSSH, MINA and parley-ssh's server do).

## Other notes

- JSch logs in with a password, a private key (file or contents) or an ssh-agent (`auth=Agent`).
- Ed25519 keys work on Java 11 and later.


## Locks and FileChannel

As parley-files-sftp describes (`PROCESS` locks, `map()` of a local copy), with two differences:

- **Atomic `createNewFile()`.** JSch can't send SFTP's exclusive-create flag, so the file is written empty under a
  name of its own and given the path by a hard link (`hardlink@openssh.com`) or, on a server without OpenSSH's
  replacing rename, by a plain rename; the server refuses either if the path is taken, so of several racing callers
  one wins. A server with the replacing rename but no hard links gets the old check-then-create, which isn't atomic. `getCapabilities()` then says so (`atomicCreateNewFile()` is false), so code that needs an atomic create, like parley-net's locks, doesn't count on it.
- **`force()` only sends the data.** JSch can't send `fsync@openssh.com`, so the server isn't asked to sync.

## Testing

`mvn verify` runs parley-files-sftp's provider test suite against this provider (the tests jar, through
surefire's `dependenciesToScan`), with `parley.files.sftp.test.unsupported=writeAtPosition` in the pom: the suite skips what the library can't do, and this project's own tests: that JSch alone on the class path is used, reads, and refuses a write at a position (`JschAloneTest`), and that opening a file sends no extra request (`JschOpenSendsNoStatTest`). See "Testing a provider" in parley-files-sftp's README; the
suite's server is chosen with `-Dparley.files.sftp.test.server` as described there.
