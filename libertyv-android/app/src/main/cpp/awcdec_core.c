// Decodificador de audio AWC (XMA2 de Xbox 360 y MP3 de PS3) usando FFmpeg (libavcodec).
// Sustituye a libav_wrapper.dll de LibertyV.
#include "awcdec_core.h"

#include <stdlib.h>
#include <string.h>

#include <libavcodec/avcodec.h>
#include <libavutil/channel_layout.h>
#include <libavutil/mem.h>

struct awc_dec {
    AVCodecContext *ctx;
    AVCodecParserContext *parser;
    AVPacket *pkt;
    AVFrame *frame;
    int is_mp3;
    int16_t *out;
    int out_len;
    int out_cap;
    int errors;
};

static int ensure_cap(awc_dec *d, int extra) {
    if (d->out_len + extra <= d->out_cap) return 0;
    int cap = d->out_cap ? d->out_cap : 4096;
    while (cap < d->out_len + extra) cap *= 2;
    int16_t *n = (int16_t *) realloc(d->out, (size_t) cap * sizeof(int16_t));
    if (!n) return -1;
    d->out = n;
    d->out_cap = cap;
    return 0;
}

static inline int16_t clip_f(float v) {
    int s = (int) (v * 32768.0f);
    if (s > 32767) s = 32767;
    if (s < -32768) s = -32768;
    return (int16_t) s;
}

// Copia el canal 0 del frame como PCM de 16 bits.
static int append_frame(awc_dec *d, const AVFrame *f) {
    int n = f->nb_samples;
    if (n <= 0) return 0;
    if (ensure_cap(d, n) < 0) return -1;
    int16_t *dst = d->out + d->out_len;
    int ch = f->ch_layout.nb_channels > 0 ? f->ch_layout.nb_channels : 1;
    switch (f->format) {
        case AV_SAMPLE_FMT_FLTP: {
            const float *s = (const float *) f->extended_data[0];
            for (int i = 0; i < n; i++) dst[i] = clip_f(s[i]);
            break;
        }
        case AV_SAMPLE_FMT_FLT: {
            const float *s = (const float *) f->extended_data[0];
            for (int i = 0; i < n; i++) dst[i] = clip_f(s[i * ch]);
            break;
        }
        case AV_SAMPLE_FMT_S16P: {
            memcpy(dst, f->extended_data[0], (size_t) n * sizeof(int16_t));
            break;
        }
        case AV_SAMPLE_FMT_S16: {
            const int16_t *s = (const int16_t *) f->extended_data[0];
            for (int i = 0; i < n; i++) dst[i] = s[i * ch];
            break;
        }
        case AV_SAMPLE_FMT_S32P: {
            const int32_t *s = (const int32_t *) f->extended_data[0];
            for (int i = 0; i < n; i++) dst[i] = (int16_t) (s[i] >> 16);
            break;
        }
        case AV_SAMPLE_FMT_S32: {
            const int32_t *s = (const int32_t *) f->extended_data[0];
            for (int i = 0; i < n; i++) dst[i] = (int16_t) (s[i * ch] >> 16);
            break;
        }
        default:
            return -1;
    }
    d->out_len += n;
    return 0;
}

static void decode_packet(awc_dec *d, AVPacket *pkt) {
    int ret = avcodec_send_packet(d->ctx, pkt);
    if (ret < 0) {
        d->errors++;
        return;
    }
    while (1) {
        ret = avcodec_receive_frame(d->ctx, d->frame);
        if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) break;
        if (ret < 0) {
            d->errors++;
            break;
        }
        if (append_frame(d, d->frame) < 0) d->errors++;
        av_frame_unref(d->frame);
    }
}

awc_dec *awc_dec_init(int is_mp3, int sample_rate) {
    const AVCodec *codec = avcodec_find_decoder(is_mp3 ? AV_CODEC_ID_MP3 : AV_CODEC_ID_XMA2);
    if (!codec) return NULL;
    awc_dec *d = (awc_dec *) calloc(1, sizeof(awc_dec));
    if (!d) return NULL;
    d->is_mp3 = is_mp3;
    d->ctx = avcodec_alloc_context3(codec);
    d->pkt = av_packet_alloc();
    d->frame = av_frame_alloc();
    if (!d->ctx || !d->pkt || !d->frame) goto fail;

    d->ctx->sample_rate = sample_rate > 0 ? sample_rate : 32000;
    av_channel_layout_default(&d->ctx->ch_layout, 1);
    if (!is_mp3) {
        // XMA2WAVEFORMATEX de 34 bytes: 1 flujo mono, sin máscara de canales.
        d->ctx->block_align = 2048;
        d->ctx->extradata = (uint8_t *) av_mallocz(34 + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!d->ctx->extradata) goto fail;
        d->ctx->extradata_size = 34;
        d->ctx->extradata[0] = 1; // NumStreams (LE16)
    }
    if (avcodec_open2(d->ctx, codec, NULL) < 0) goto fail;
    if (is_mp3) {
        d->parser = av_parser_init(AV_CODEC_ID_MP3);
        if (!d->parser) goto fail;
    }
    return d;

fail:
    awc_dec_free(d);
    return NULL;
}

int awc_dec_feed(awc_dec *d, const uint8_t *data, int len) {
    d->out_len = 0;
    if (!d->is_mp3) {
        // Un paquete XMA (2048 bytes) por llamada; FFmpeg necesita relleno al final.
        uint8_t *buf = (uint8_t *) av_malloc((size_t) len + AV_INPUT_BUFFER_PADDING_SIZE);
        if (!buf) return -1;
        memcpy(buf, data, (size_t) len);
        memset(buf + len, 0, AV_INPUT_BUFFER_PADDING_SIZE);
        if (av_packet_from_data(d->pkt, buf, len) < 0) {
            av_free(buf);
            return -1;
        }
        decode_packet(d, d->pkt);
        av_packet_unref(d->pkt);
    } else {
        while (len > 0) {
            uint8_t *out_data = NULL;
            int out_size = 0;
            int used = av_parser_parse2(d->parser, d->ctx, &out_data, &out_size, data, len,
                                        AV_NOPTS_VALUE, AV_NOPTS_VALUE, 0);
            if (used < 0) return -1;
            data += used;
            len -= used;
            if (out_size > 0) {
                d->pkt->data = out_data;
                d->pkt->size = out_size;
                decode_packet(d, d->pkt);
            }
            if (used == 0 && out_size == 0) break;
        }
    }
    return d->out_len;
}

const int16_t *awc_dec_output(awc_dec *d) { return d->out; }

int awc_dec_errors(awc_dec *d) { return d->errors; }

void awc_dec_free(awc_dec *d) {
    if (!d) return;
    if (d->parser) av_parser_close(d->parser);
    if (d->ctx) avcodec_free_context(&d->ctx);
    if (d->pkt) av_packet_free(&d->pkt);
    if (d->frame) av_frame_free(&d->frame);
    free(d->out);
    free(d);
}
