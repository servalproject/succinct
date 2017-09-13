/*
Copyright (C) 2012 Paul Gardner-Stephen
 
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

int utf16toutf8(const unsigned short *in, unsigned in_len, char *out, unsigned *out_len) {
    int i;
    unsigned buff_size = *out_len;
    unsigned len = 0;
    int ret=-1;
    for (i = 0; i < in_len; i++) {
        int codepoint = in[i];
        if (codepoint < 0x80) {
            if (len+1 >= buff_size) goto error; // UTF8 string too long
            out[len++] = (char) codepoint;
        } else if (codepoint < 0x0800) {
            if (len+2 >= buff_size) goto error; // UTF8 string too long
            out[len++] = (char) (0xc0 + (codepoint >> 6));
            out[len++] = (char) (0x80 + (codepoint & 0x3f));
        } else {
            if (len+3 >= buff_size) goto error; // UTF8 string too long
            out[len++] = (char) (0xe0 + (codepoint >> 12));
            out[len++] = (char) (0x80 + ((codepoint >> 6) & 0x3f));
            out[len++] = (char) (0x80 + (codepoint & 0x3f));
        }
    }
    ret=0;
error:
    *out_len = len;
    return ret;
}

int utf8toutf16(const char *in, unsigned in_len, unsigned short *out, unsigned  *out_len) {
    int i;
    unsigned buff_size = *out_len;
    unsigned len = 0;
    int ret=-1;

    for (i = 0; i < in_len; i++) {
        if (len+1 >= buff_size)
            goto error;

        unsigned short unicode = 0;

        if ((in[i] & 0xc0) == 0x80) {
            /* String begins with a UTF8 continuation character, or has a continuation
               character out of place.
               This is not allowed (not in the least because we use exactly this
               construction to indicate a compressed message, and so never need to
               have a compressed message be longer than uncompressed, because
               uncompressed messages are valid in place of compressed messages). */
            goto error;
        } else if ((in[i] & 0xc0) < 0x80) {
            // natural character
            unicode = (unsigned short) in[i];
        } else {
            // UTF character
            if (in[i] < 0xe0) {
                if (in_len - i < 1) goto error; // string ends mid-way through a UTF8 sequence
                // 2 bytes
                unicode = (unsigned short) (((in[i] & 0x1f) << 6) | (in[i + 1] & 0x3f));
                i++;
            } else if (in[i] < 0xf8) {
                if (in_len - i < 2) goto error; // string ends mid-way through a UTF8 sequence
                // 3 bytes
                unicode = (unsigned short) (((in[i] & 0x0f) << 12) | ((in[i + 1] & 0x3f) << 6) | (in[i + 2] & 0x3f));
                i += 2;
            } else {
                // UTF8 no longer supports >3 byte sequences
                goto error;
            }
        }
        out[len++] = unicode;
    }
    ret=0;
error:
    *out_len = len;
    return ret;
}

