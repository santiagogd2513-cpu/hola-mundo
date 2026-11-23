#ifndef AWCDEC_CORE_H
#define AWCDEC_CORE_H

#include <stdint.h>

typedef struct awc_dec awc_dec;

/** is_mp3 = 1 para PS3 (MP3), 0 para Xbox 360 (XMA2). Devuelve NULL si falla. */
awc_dec *awc_dec_init(int is_mp3, int sample_rate);

/** Decodifica datos y devuelve cuántas muestras quedaron en awc_dec_output() (o <0 si error). */
int awc_dec_feed(awc_dec *d, const uint8_t *data, int len);

const int16_t *awc_dec_output(awc_dec *d);

/** Paquetes que FFmpeg no pudo decodificar (se ignoran). */
int awc_dec_errors(awc_dec *d);

void awc_dec_free(awc_dec *d);

#endif
