/*
  Compress a key value pair file according to a recipe.
  The recipe indicates the type of each field.
  For certain field types the precision or range of allowed
  values can be specified to further aid compression.
*/

#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <strings.h>
#include <math.h>
#include <time.h>

#ifdef ANDROID

#include <android/log.h>

#define LOGI(...) ((void)__android_log_print(ANDROID_LOG_INFO, "libsmac", __VA_ARGS__))
#define LOGE(...) ((void)__android_log_print(ANDROID_LOG_ERROR, "libsmac", __VA_ARGS__))
#else
#define LOGI(...)
#define LOGE(...)
#endif

/*
#include "charset.h"
#include "visualise.h"
#include "packed_stats.h"
*/

#include "arithmetic.h"
#include "recipe.h"
#include "md5.h"
#include "smac.h"

#ifdef ANDROID

time_t timegm(struct tm *tm) {
    time_t ret;
    char *tz;

    tz = getenv("TZ");
    setenv("TZ", "", 1);
    tzset();
    ret = mktime(tm);
    if (tz)
        setenv("TZ", tz, 1);
    else
        unsetenv("TZ");
    tzset();
    return ret;
}

#endif

static int recipe_parse_fieldtype(char *name) {
    if (!strcasecmp(name, "integer")) return FIELDTYPE_INTEGER;
    if (!strcasecmp(name, "int")) return FIELDTYPE_INTEGER;
    if (!strcasecmp(name, "float")) return FIELDTYPE_FLOAT;
    if (!strcasecmp(name, "decimal")) return FIELDTYPE_FIXEDPOINT;
    if (!strcasecmp(name, "fixedpoint")) return FIELDTYPE_FIXEDPOINT;
    if (!strcasecmp(name, "boolean")) return FIELDTYPE_BOOLEAN;
    if (!strcasecmp(name, "bool")) return FIELDTYPE_BOOLEAN;
    if (!strcasecmp(name, "timeofday")) return FIELDTYPE_TIMEOFDAY;
    if (!strcasecmp(name, "timestamp")) return FIELDTYPE_TIMEDATE;
    if (!strcasecmp(name, "datetime")) return FIELDTYPE_TIMEDATE;
    if (!strcasecmp(name, "magpitimestamp")) return FIELDTYPE_MAGPITIMEDATE;
    if (!strcasecmp(name, "date")) return FIELDTYPE_DATE;
    if (!strcasecmp(name, "latlong")) return FIELDTYPE_LATLONG;
    if (!strcasecmp(name, "geopoint")) return FIELDTYPE_LATLONG;
    if (!strcasecmp(name, "text")) return FIELDTYPE_TEXT;
    if (!strcasecmp(name, "string")) return FIELDTYPE_TEXT;
    if (!strcasecmp(name, "image")) return FIELDTYPE_TEXT;
    if (!strcasecmp(name, "information")) return FIELDTYPE_TEXT;
    if (!strcasecmp(name, "uuid")) return FIELDTYPE_UUID;
    if (!strcasecmp(name, "magpiuuid")) return FIELDTYPE_MAGPIUUID;
    if (!strcasecmp(name, "enum")) return FIELDTYPE_ENUM;

    return -1;
}

static char *recipe_field_type_name(int f) {
    switch (f) {
        case FIELDTYPE_INTEGER:
            return "integer";
        case FIELDTYPE_FLOAT:
            return "float";
        case FIELDTYPE_FIXEDPOINT:
            return "fixedpoint";
        case FIELDTYPE_BOOLEAN:
            return "boolean";
        case FIELDTYPE_TIMEOFDAY:
            return "timeofday";
        case FIELDTYPE_TIMEDATE:
            return "timestamp";
        case FIELDTYPE_MAGPITIMEDATE:
            return "magpitimestamp";
        case FIELDTYPE_DATE:
            return "date";
        case FIELDTYPE_LATLONG:
            return "latlong";
        case FIELDTYPE_TEXT:
            return "text";
        case FIELDTYPE_UUID:
            return "uuid";
        case FIELDTYPE_MAGPIUUID:
            return "magpiuuid";
        default:
            return "unknown";
    }
}

void recipe_free(struct recipe *recipe) {
    int i;
    for (i = 0; i < recipe->field_count; i++) {
        if (recipe->fields[i].name)
            free(recipe->fields[i].name);
        recipe->fields[i].name = NULL;
        int e;
        for (e = 0; e < recipe->fields[i].enum_count; e++) {
            if (recipe->fields[i].enum_values[e]) {
                free(recipe->fields[i].enum_values[e]);
                recipe->fields[i].enum_values[e] = NULL;
            }
        }
    }
    free(recipe);
}

static int recipe_form_hash(char *recipe_file, unsigned char *formhash, char *formname) {
    MD5_CTX md5;
    unsigned char hash[16];

    // Get basename of form for computing hash
    char recipe_name[1024];
    int start = 0;
    int end = strlen(recipe_file);
    int i;
    // Cut path from filename
    for (i = 0; recipe_file[i]; i++)
        if (recipe_file[i] == '/')
            start = i + 1;
    // Cut .recipe from filename
    if (end > strlen(".recipe"))
        if (!strcasecmp(".recipe", &recipe_file[end - strlen(".recipe")]))
            end = end - strlen(".recipe") - 1;
    int j = 0;
    for (i = start; i <= end; i++)
        recipe_name[j++] = recipe_file[i];
    recipe_name[j] = 0;

    MD5_Init(&md5);
    LOGI("Calculating recipe file formhash from '%s' (%d chars)\n",
         recipe_name, (int) strlen(recipe_name));
    MD5_Update(&md5, recipe_name, strlen(recipe_name));
    MD5_Final(hash, &md5);

    bcopy(hash, formhash, 6);

    if (formname)
        strcpy(formname, recipe_name);
    return 0;
}

struct recipe *recipe_read(char *formname, char *buffer, int buffer_size) {
    if (buffer_size < 1 || buffer_size > 1048576) {
        LOGE("Recipe file empty or too large (>1MB).");
        return NULL;
    }

    struct recipe *recipe = calloc(sizeof(struct recipe), 1);
    if (!recipe) {
        LOGE("Allocation of recipe structure failed.");
        return NULL;
    }

    // Get recipe hash
    recipe_form_hash(formname, recipe->formhash, recipe->formname);
    LOGI("recipe_read(): Computing formhash based on form name '%s'", formname);

    int i;
    int l = 0;
    int line_number = 1;
    char line[1024];
    char name[1024], type[1024];
    int min, max, precision;
    char enumvalues[1024];

    for (i = 0; i <= buffer_size; i++) {
        if (l > 1000) {
            LOGE("line:%d:Line too long.", line_number);
            recipe_free(recipe);
            return NULL;
        }
        if ((i == buffer_size) || (buffer[i] == '\n') || (buffer[i] == '\r')) {
            if (recipe->field_count > 1000) {
                LOGE("line:%d:Too many field definitions (must be <=1000).", line_number);
                recipe_free(recipe);
                return NULL;
            }
            // Process recipe line
            line[l] = 0;
            if ((l > 0) && (line[0] != '#')) {
                enumvalues[0] = 0;
                if (sscanf(line, "%[^:]:%[^:]:%d:%d:%d:%[^\n]",
                           name, type, &min, &max, &precision, enumvalues) >= 5) {
                    int fieldtype = recipe_parse_fieldtype(type);
                    if (fieldtype == -1) {
                        LOGE("line:%d:Unknown or misspelled field type '%s'.", 
                             line_number, type);
                        recipe_free(recipe);
                        return NULL;
                    } else {
                        // Store parsed field
                        recipe->fields[recipe->field_count].name = strdup(name);
                        recipe->fields[recipe->field_count].type = fieldtype;
                        recipe->fields[recipe->field_count].minimum = min;
                        recipe->fields[recipe->field_count].maximum = max;
                        recipe->fields[recipe->field_count].precision = precision;

                        if (fieldtype == FIELDTYPE_ENUM) {
                            char enum_value[1024];
                            int e = 0;
                            int en = 0;
                            int i;
                            for (i = 0; i <= strlen(enumvalues); i++) {
                                if ((enumvalues[i] == ',') || (enumvalues[i] == 0)) {
                                    // End of field
                                    enum_value[e] = 0;
                                    if (en >= 32) {
                                        LOGE("line:%d:enum has too many values (max=32)",
                                                 line_number);
                                        recipe_free(recipe);
                                        return NULL;
                                    }
                                    recipe->fields[recipe->field_count].enum_values[en]
                                            = strdup(enum_value);
                                    en++;
                                    e = 0;
                                } else {
                                    // next character of field
                                    enum_value[e++] = enumvalues[i];
                                }
                            }
                            if (en < 1) {
                                LOGE("line:%d:Malformed enum field definition: must contain at least one value option.",
                                         line_number);
                                recipe_free(recipe);
                                return NULL;
                            }
                            recipe->fields[recipe->field_count].enum_count = en;
                        }

                        recipe->field_count++;
                    }
                } else {
                    LOGE("line:%d:Malformed field definition.",
                             line_number);
                    recipe_free(recipe);
                    return NULL;
                }
            }
            line_number++;
            l = 0;
        } else {
            line[l++] = buffer[i];
        }
    }
    return recipe;
}

static int recipe_parse_boolean(char *b) {
    if (!b) return 0;
    switch (b[0]) {
        case 'y':
        case 'Y':
        case 't':
        case 'T':
        case '1':
            return 1;
        default:
            return 0;
    }
}

static uint8_t parseHexDigit(char c) {
    if (c >= '0' && c <= '9') return (uint8_t)(c - '0');
    if (c >= 'A' && c <= 'F') return (uint8_t)(c - 'A' + 10);
    if (c >= 'a' && c <= 'f') return (uint8_t)(c - 'a' + 10);
    return 0;
}

static uint8_t parseHexByte(char *hex) {
    return (parseHexDigit(hex[0]) << 4) | parseHexDigit(hex[1]);
}

static int recipe_encode_field(struct recipe *recipe, stats_handle *stats, range_coder *c,
                        int fieldnumber, char *value) {
    int normalised_value;
    int minimum;
    int maximum;
    int precision;
    int h, m, s, d, y;
    float lat, lon;
    int ilat, ilon;

    precision = recipe->fields[fieldnumber].precision;

    switch (recipe->fields[fieldnumber].type) {
        case FIELDTYPE_INTEGER:
            minimum = recipe->fields[fieldnumber].minimum;
            maximum = recipe->fields[fieldnumber].maximum;
            if (maximum <= minimum) {
                LOGI("Illegal range: min=%d, max=%d", minimum, maximum);
                return -1;
            }
            normalised_value = atoi(value) - minimum;
            if (normalised_value < 0 || normalised_value > maximum){
                LOGI("Failed to convert integer %s, to fit within range; min=%d, max=%d", value, minimum, maximum);
                return -1;
            }
            return range_encode_equiprobable(c, maximum - minimum + 1, normalised_value);
        case FIELDTYPE_FLOAT: {
            float f = atof(value);
            int sign = 0;
            int exponent = 0;
            int mantissa = 0;
            if (f < 0) {
                sign = 1;
                f = -f;
            }
            else sign = 0;
            double m = frexp(f, &exponent);
            mantissa = m * 0xffffff;
            if (exponent < -127) exponent = -127;
            if (exponent > 127) exponent = 127;
            LOGI("encoding sign=%d, exp=%d, mantissa=%x, f=%f",
                    sign, exponent, mantissa, atof(value));
            // Sign
            range_encode_equiprobable(c, 2, sign);
            // Exponent
            range_encode_equiprobable(c, 256, exponent + 128);
            // Mantissa
            range_encode_equiprobable(c, 256, (mantissa >> 16) & 0xff);
            range_encode_equiprobable(c, 256, (mantissa >> 8) & 0xff);
            return range_encode_equiprobable(c, 256, (mantissa >> 0) & 0xff);
        }
        case FIELDTYPE_FIXEDPOINT:
        case FIELDTYPE_BOOLEAN:
            normalised_value = recipe_parse_boolean(value);
            minimum = 0;
            maximum = 1;
            return range_encode_equiprobable(c, maximum - minimum + 1, normalised_value);
        case FIELDTYPE_TIMEOFDAY:
            if (sscanf(value, "%d:%d.%d", &h, &m, &s) < 2) return -1;
            // XXX - We don't support leap seconds
            if (h < 0 || h > 23 || m < 0 || m > 59 || s < 0 || s > 59) return -1;
            normalised_value = h * 3600 + m * 60 + s;
            minimum = 0;
            maximum = 24 * 60 * 60;
            if (precision == 0) precision = 17; // 2^16 < 24*60*60 < 2^17
            if (precision < 17) {
                normalised_value = normalised_value >> (17 - precision);
                minimum = minimum >> (17 - precision);
                maximum = maximum >> (17 - precision);
                maximum += 1; // make sure that normalised_value cannot = maximum
            }
            return range_encode_equiprobable(c, maximum - minimum + 1, normalised_value);
        case FIELDTYPE_TIMEDATE: {
            struct tm tm;
            int tzh = 0, tzm = 0;
            int r;
            bzero(&tm, sizeof(tm));
            if ((r = sscanf(value, "%d-%d-%dT%d:%d:%d.%*d+%d:%d",
                            &tm.tm_year, &tm.tm_mon, &tm.tm_mday,
                            &tm.tm_hour, &tm.tm_min, &tm.tm_sec,
                            &tzh, &tzm)) < 6) {
                LOGE("r=%d\n", r);
                return -1;
            }
#if defined(__sgi) || defined(__sun)
#else
            tm.tm_gmtoff = tzm * 60 + tzh * 3600;
#endif
            tm.tm_year -= 1900;
            tm.tm_mon -= 1;
            time_t t = mktime(&tm);
            minimum = 1;
            maximum = 0x7fffffff;
            normalised_value = t;

            int b;
            b = range_encode_equiprobable(c, 0x8000, t >> 16);
            b = range_encode_equiprobable(c, 0x10000, t & 0xffff);
            LOGI("TIMEDATE: encoding t=%d\n", (int) t);
            return b;
        }
        case FIELDTYPE_MAGPITIMEDATE: {
            struct tm tm;
            // int tzh=0,tzm=0;
            int r;
            bzero(&tm, sizeof(tm));
            if ((r = sscanf(value, "%d-%d-%d %d:%d:%d",
                            &tm.tm_year, &tm.tm_mon, &tm.tm_mday,
                            &tm.tm_hour, &tm.tm_min, &tm.tm_sec)) < 6) {
                LOGE("r=%d", r);
                return -1;
            }

            // Validate fields
            if (tm.tm_year < 0 || tm.tm_year > 9999) return -1;
            if (tm.tm_mon < 1 || tm.tm_mon > 12) return -1;
            if (tm.tm_mday < 1 || tm.tm_mday > 31) return -1;
            if (tm.tm_hour < 0 || tm.tm_hour > 24) return -1;
            if (tm.tm_min < 0 || tm.tm_min > 59) return -1;
            if (tm.tm_sec < 0 || tm.tm_sec > 61) return -1;

            // Encode each field: requires about 40 bits, but safely encodes all values
            // without risk of timezone munging on Android
            range_encode_equiprobable(c, 10000, tm.tm_year);
            range_encode_equiprobable(c, 12, tm.tm_mon - 1);
            range_encode_equiprobable(c, 31, tm.tm_mday - 1);
            range_encode_equiprobable(c, 25, tm.tm_hour);
            range_encode_equiprobable(c, 60, tm.tm_min);
            return range_encode_equiprobable(c, 62, tm.tm_sec);
        }
        case FIELDTYPE_DATE:
            // ODK does YYYY/MM/DD
            // Magpi does DD-MM-YYYY
            // The different delimiter allows us to discern between the two
            LOGI("Parsing FIELDTYPE_DATE value '%s'", value);
            if (sscanf(value, "%d/%d/%d", &y, &m, &d) == 3) {}
            else if (sscanf(value, "%d-%d-%d", &d, &m, &y) == 3) {}
            else return -1;

            // XXX Not as efficient as it could be (assumes all months have 31 days)
            if (y < 1 || y > 9999 || m < 1 || m > 12 || d < 1 || d > 31) {
                LOGE("Invalid field value");
                return -1;
            }
            normalised_value = y * 372 + (m - 1) * 31 + (d - 1);
            minimum = 0;
            maximum = 10000 * 372;
            if (precision == 0) precision = 22; // 2^21 < maximum < 2^22
            if (precision < 22) {
                normalised_value = normalised_value >> (22 - precision);
                minimum = minimum >> (22 - precision);
                maximum = maximum >> (22 - precision);
                maximum += 1; // make sure that normalised_value cannot = maximum
            }
            return range_encode_equiprobable(c, maximum - minimum + 1, normalised_value);
        case FIELDTYPE_LATLONG:
            if (sscanf(value, "%f %f", &lat, &lon) != 2
             && sscanf(value, "%f,%f", &lat, &lon) != 2) return -1;
            if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return -1;
            ilat = lroundf(lat);
            ilon = lroundf(lon);
            ilat += 90; // range now 0..181 (for -90 to +90, inclusive)
            ilon += 180; // range now 0..360 (for -180 to +180, inclusive)
            if (precision == 16) {
                // gradicule resolution
                range_encode_equiprobable(c, 182, ilat);
                return range_encode_equiprobable(c, 361, ilon);
            } else if (precision == 0 || precision == 34) {
                // ~1m resolution
                ilat = lroundf(lat * 112000);
                ilon = lroundf(lon * 112000);
                ilat += 90 * 112000; // range now 0..181 (for -90 to +90, inclusive)
                ilon += 180 * 112000; // range now 0..359 (for -179 to +180, inclusive)
                // encode latitude
                range_encode_equiprobable(c, 182 * 112000, ilat);
                return range_encode_equiprobable(c, 361 * 112000, ilon);
            } else
                return -1;
        case FIELDTYPE_ENUM: {
            for (normalised_value = 0;
                 normalised_value < recipe->fields[fieldnumber].enum_count;
                 normalised_value++) {
                if (!strcasecmp(value,
                                recipe->fields[fieldnumber].enum_values[normalised_value]))
                    break;
            }
            if (normalised_value >= recipe->fields[fieldnumber].enum_count) {
                LOGE("Value '%s' is not in enum list for '%s'.",
                        value,
                        recipe->fields[fieldnumber].name);
                return -1;
            }
            maximum = recipe->fields[fieldnumber].enum_count;
            LOGI("enum: encoding %s as %d of %d", value, normalised_value, maximum);
            return range_encode_equiprobable(c, maximum, normalised_value);
        }
        case FIELDTYPE_TEXT: {
            int before = c->bits_used;
            // Trim to precision specified length if non-zero
            if (recipe->fields[fieldnumber].precision > 0) {
                if (strlen(value) > recipe->fields[fieldnumber].precision)
                    value[recipe->fields[fieldnumber].precision] = 0;
            }
            int r = stats3_compress_append(c, (unsigned char *) value, strlen(value), stats,
                                           NULL);
            LOGI("'%s' encoded in %d bits", value, c->bits_used - before);
            if (r) return -1;
            return 0;
        }
        case FIELDTYPE_MAGPIUUID:
            // 64bit hex followed by milliseconds since UNIX epoch (48 bits to last us many centuries)
        {
            int i, j = 0;
            unsigned char uuid[8];
            i = 0;
            for (i = 0; i < 16; i += 2) {
                uuid[j] = parseHexByte(&value[i]);
                range_encode_equiprobable(c, 256, uuid[j++]);
            }
            long long timestamp = strtoll(&value[17], NULL, 10);
            timestamp &= 0xffffffffffffLL;
            for (i = 0; i < 6; i++) {
                int b = (timestamp >> 40LL) & 0xff;
                range_encode_equiprobable(c, 256, b);
                timestamp = timestamp << 8LL;
            }
            return 0;
        }
        case FIELDTYPE_UUID: {
            // Parse out the 128 bits (=16 bytes) of UUID, and encode as much as we have been asked.
            // XXX Will accept all kinds of rubbish
            int i, j = 0;
            unsigned char uuid[16];
            i = 0;
            if (!strncasecmp(value, "uuid:", 5)) i = 5;
            for (; value[i]; i++) {
                if (j == 16) {
                    j = 17;
                    break;
                }
                if (value[i] != '-') {
                    uuid[j++] = parseHexByte(&value[i]);
                    i++;
                }
            }
            if (j != 16) {
                LOGE("Malformed UUID field.");
                return -1;
            }
            // write appropriate number of bytes
            int precision = recipe->fields[fieldnumber].precision;
            if (precision < 1 || precision > 16) precision = 16;
            for (i = 0; i < precision; i++) {
                range_encode_equiprobable(c, 256, uuid[i]);
            }
            return 0;
        }
    }

    return -1;
}

int recipe_compress(stats_handle *h, struct recipe *recipe,
                    const char *in, int in_len, unsigned char *out, int out_size) {
    /*
    Eventually we want to support full skip logic, repeatable sections and so on.
    For now we will allow skip sections by indicating missing fields.
    This approach lets us specify fields implictly by their order in the recipe
    (NOT in the completed form).
    This entails parsing the completed form, and then iterating through the RECIPE
    and considering each field in turn.  A single bit per field will be used to
    indicate whether it is present.  This can be optimised later.
  */


    if (!recipe) {
        LOGE("No recipe provided.");
        return -1;
    }
    if (!in) {
        LOGE("No input provided.");
        return -1;
    }
    if (!out) {
        LOGE("No output buffer provided.");
        return -1;
    }

    // Make new range coder with 1KB of space
    range_coder *c = range_new_coder(1024);
    if (!c) {
        LOGE("Could not instantiate range coder.");
        return -1;
    }

    // Write form hash first
    int i;
    LOGI("form hash = %02x%02x%02x%02x%02x%02x",
           recipe->formhash[0],
           recipe->formhash[1],
           recipe->formhash[2],
           recipe->formhash[3],
           recipe->formhash[4],
           recipe->formhash[5]);
    for (i = 0; i < sizeof(recipe->formhash); i++)
        range_encode_equiprobable(c, 256, recipe->formhash[i]);

    char *keys[1024];
    char *values[1024];
    int value_count = 0;

    int l = 0;
    int line_number = 1;
    char line[1024];
    char key[1024], value[1024];

    for (i = 0; i <= in_len; i++) {
        if (l > 1000) {
            LOGE("line:%d:Data line too long.\n", line_number);
            return -1;
        }
        if ((i == in_len) || (in[i] == '\n') || (in[i] == '\r')) {
            if (value_count > 1000) {
                LOGE("line:%d:Too many data lines (must be <=1000).\n",
                         line_number);
                return -1;
            }
            // Process key=value line
            line[l] = 0;
            if ((l > 0) && (line[0] != '#')) {
                if (sscanf(line, "%[^=]=%[^\n]", key, value) == 2) {
                    keys[value_count] = strdup(key);
                    values[value_count] = strdup(value);
                    value_count++;
                } else {
                    LOGE("line:%d:Malformed data line (%s:%d): '%s'\n",
                             line_number, __FILE__, __LINE__, line);
                    return -1;
                }
            }
            line_number++;
            l = 0;
        } else {
            line[l++] = in[i];
        }
    }
    LOGI("Read %d data lines, %d values.", line_number, value_count);

    int field;

    for (field = 0; field < recipe->field_count; field++) {
        // look for this field in keys[]
        for (i = 0; i < value_count; i++) {
            if (!strcasecmp(keys[i], recipe->fields[field].name)) break;
        }
        if (i < value_count) {
            // Field present
            LOGI("Found field #%d ('%s')", field, recipe->fields[field].name);
            // Record that the field is present.
            range_encode_equiprobable(c, 2, 1);
            // Now, based on type of field, encode it.
            if (recipe_encode_field(recipe, h, c, field, values[i])) {
                range_coder_free(c);
                LOGE("Could not record value '%s' for field '%s' (type %d)",
                         values[i], recipe->fields[field].name,
                         recipe->fields[field].type);
                return -1;
            }
            LOGI(" ... encoded value '%s'", values[i]);
        } else {
            // Field missing: record this fact and nothing else.
            LOGE("No field #%d ('%s')", field, recipe->fields[field].name);
            range_encode_equiprobable(c, 2, 0);
        }
    }

    // Get result and store it, unless it is too big for the output buffer
    range_conclude(c);
    int bytes = (c->bits_used / 8) + ((c->bits_used & 7) ? 1 : 0);
    if (bytes > out_size) {
        range_coder_free(c);
        LOGE("Compressed data too big for output buffer\n");
        return -1;
    }

    bcopy(c->bit_stream, out, bytes);
    range_coder_free(c);

    LOGI("Used %d bits (%d bytes).", c->bits_used, bytes);

    return bytes;
}

int recipe_stripped_to_csv_line(struct recipe *recipe,
                                char *output_dir,
                                char *stripped, int stripped_data_len,
                                char *csv_out, int csv_out_size) {
    if (csv_out_size < 8192) {
        LOGI("Not enough space to extract CSV line.\n");
        return -1;
    }

    int state = 0;
    int i;

    char *fieldnames[1024];
    char *values[1024];
    int field_count = 0;

    char field[1024];
    int field_len = 0;

    char value[1024];
    int value_len = 0;

    // Read fields from stripped.
    for (i = 0; i < stripped_data_len; i++) {
        if (stripped[i] == '=' && (state == 0)) {
            state = 1;
        } else if (stripped[i] < ' ') {
            if (state == 1) {
                // record field=value pair
                field[field_len] = 0;
                value[value_len] = 0;
                fieldnames[field_count] = strdup(field);
                values[field_count] = strdup(value);
                field_count++;
            }
            state = 0;
            field_len = 0;
            value_len = 0;
        } else {
            if (field_len > 1000 || value_len > 1000) return -1;
            if (state == 0) field[field_len++] = stripped[i];
            else value[value_len++] = stripped[i];
        }
    }

    unsigned n = 0;
    unsigned f;

    for (f = 0; f < recipe->field_count; f++) {
        char *v = "";
        for (i = 0; i < field_count; i++) {
            if (!strcasecmp(fieldnames[i], recipe->fields[f].name)) {
                v = values[i];
                break;
            }
        }
        n += snprintf(&csv_out[n], 8192 - n, "%s%s", f ? "," : "", v);
    }

    csv_out[n++] = '\n';
    csv_out[n] = 0;

    return 0;
}
