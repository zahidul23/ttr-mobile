/*
 * Stand-in for Termux/Winlator's libandroid-sysvshm.so. Android has no SysV shared memory and
 * our X server does not offer MIT-SHM, so Wine's X11 driver uses plain image transfers; these
 * entry points only need to exist and report "not supported".
 */
#include <errno.h>
#include <stddef.h>
#include <sys/types.h>

struct shmid_ds;
int shmget(key_t key, size_t size, int flags) { (void)key; (void)size; (void)flags; errno = ENOSYS; return -1; }
void *shmat(int id, const void *address, int flags) { (void)id; (void)address; (void)flags; errno = ENOSYS; return (void *)-1; }
int shmdt(const void *address) { (void)address; errno = ENOSYS; return -1; }
int shmctl(int id, int command, struct shmid_ds *buffer) { (void)id; (void)command; (void)buffer; errno = ENOSYS; return -1; }
