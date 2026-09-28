/*
 * Reserving space on filesystems that cannot.
 *
 * Before a download the client sizes every file of the install up front. exFAT, the format of
 * most SD cards, has no fallocate, and the fallback - glibc's posix_fallocate, or a caller of its
 * own - writes into every block to force the allocation: millions of writes for a large game,
 * each one stopped by proot's tracer and carried over FUSE to the card. That is the likely cause of
 * a "Reserving space" on the card that outlasted the download itself.
 *
 * So when the filesystem refuses, the file is extended to its final size with one ftruncate
 * instead, which exFAT does without writing the blocks. Nothing is actually reserved: a card that
 * fills up fails the write that runs out, which is what the client already handles for any other
 * write error. Filesystems that support fallocate get the real call.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

static int (*real_fallocate(void))(int, int, off_t, off_t) {
  static int (*fn)(int, int, off_t, off_t);
  if (!fn)
    fn = dlsym(RTLD_NEXT, "fallocate");
  return fn;
}

/* Grows fd to at least offset+len. 0, or an errno value. */
static int extend(int fd, off_t offset, off_t len) {
  static int said;
  struct stat st;
  if (offset < 0 || len <= 0)
    return EINVAL;
  if (fstat(fd, &st) != 0)
    return errno;
  if (!S_ISREG(st.st_mode))
    return EOPNOTSUPP;
  if (!said) {
    said = 1;
    fprintf(stderr, "== blsession: fallocate unsupported here; extending files instead of writing every block\n");
  }
  if (offset + len > st.st_size && ftruncate(fd, offset + len) != 0)
    return errno;
  return 0;
}

int fallocate(int fd, int mode, off_t offset, off_t len) {
  int (*fn)(int, int, off_t, off_t) = real_fallocate();
  if (!fn) {
    errno = ENOSYS;
    return -1;
  }
  if (fn(fd, mode, offset, len) == 0)
    return 0;
  /* Only a plain allocation is emulated; hole punching and the like still fail as they did. With
   * FALLOC_FL_KEEP_SIZE there is nothing to do without real allocation, and it is only a hint. */
  if (errno != EOPNOTSUPP || (mode & ~FALLOC_FL_KEEP_SIZE))
    return -1;
  if (mode & FALLOC_FL_KEEP_SIZE)
    return 0;
  int err = extend(fd, offset, len);
  if (err) {
    errno = err;
    return -1;
  }
  return 0;
}

int posix_fallocate(int fd, off_t offset, off_t len) {
  int (*fn)(int, int, off_t, off_t) = real_fallocate();
  if (fn && fn(fd, 0, offset, len) == 0)
    return 0;
  if (fn && errno != EOPNOTSUPP)
    return errno;
  return extend(fd, offset, len);
}

#ifdef __USE_LARGEFILE64
int fallocate64(int fd, int mode, off64_t offset, off64_t len) { return fallocate(fd, mode, offset, len); }

int posix_fallocate64(int fd, off64_t offset, off64_t len) { return posix_fallocate(fd, offset, len); }
#endif
