/*
(C) Paul Gardner-Stephen 2012-2013

This program is free software; you can redistribute it and/or
modify it under the terms of the GNU General Public License
as published by the Free Software Foundation; either version 2
of the License, or (at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
*/

#ifndef SUCCINCT_SMAC_H
#define SUCCINCT_SMAC_H

#include "arithmetic.h"

#ifdef __cplusplus
extern "C" {
#endif

int stats3_compress(const char *in, unsigned inlen, unsigned char *out, unsigned *outlen,
                    stats_handle *h);

int stats3_compress_bits(range_coder *c, const char *m, unsigned len, stats_handle *h,
                         double *entropyLog);

int stats3_compress_append(range_coder *c, const char *m_in, unsigned m_in_len,
                           stats_handle *h, double *entropyLog);

int stats3_decompress(const unsigned char *in, unsigned inlen, char *out, unsigned *outlen,
                      stats_handle *h);

int stats3_decompress_bits(range_coder *c, char *m, unsigned *len_out,
                           stats_handle *h, double *entropyLog);

#ifdef __cplusplus
}
#endif

#endif