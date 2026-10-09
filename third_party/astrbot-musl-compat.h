#ifndef ASTRBOT_MUSL_COMPAT_H
#define ASTRBOT_MUSL_COMPAT_H
#ifndef __ASSEMBLER__
#include <linux/stat.h>
#include <errno.h>
#ifndef MSG_COPY
#define MSG_COPY 040000
#endif
#ifndef TEMP_FAILURE_RETRY
#define TEMP_FAILURE_RETRY(expression) \
  (__extension__ ({ long int result; do result = (long int) (expression); \
  while (result == -1L && errno == EINTR); result; }))
#endif
#endif
#endif
