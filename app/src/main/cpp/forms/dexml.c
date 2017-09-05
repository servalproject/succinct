#include <stdio.h>
#include <strings.h>
#include <unistd.h>

#include "recipe.h"

int xml2stripped(const char *form_name, const char *xml, size_t xml_len,
                 char *stripped, size_t stripped_size) {

    char tag[1024];
    unsigned taglen = 0;

    char value[1024];
    unsigned val_len = 0;

    int in_instance = 0;

    int interesting_tag = 0;

    int state = 0;

    unsigned xmlofs = 0;
    unsigned stripped_ofs = 0;

    char exit_tag[1024] = "";

    char c = xml[xmlofs++];
    while (c && (xmlofs < xml_len)) {
        switch (c) {
            case '\n':
            case '\r':
                break;
            case '<':
                state = 1;
                if (interesting_tag && val_len > 0) {
                    value[val_len] = 0;
                    // Magpi puts ~ in empty fields -- don't include these in the stripped output
                    if ((value[0] == '~') && (val_len == 1)) {
                        // nothing to do
                    } else {
                        int b = snprintf(&stripped[stripped_ofs], stripped_size - stripped_ofs,
                                         "%s=%s\n", tag, value);
                        if (b > 0) stripped_ofs += b;
                    }
                    val_len = 0;
                }
                interesting_tag = 0;
                break;
            case '>':
                if (taglen) {
                    // got a tag name
                    tag[taglen] = 0;
                    interesting_tag = 0;
                    if (tag[0] != '/' && in_instance && tag[taglen - 1] != '/') {
                        interesting_tag = 1;
                    }
                    if (!form_name) {
                        /*
                          Magpi forms don't include the form name in the xml.
                          We have to get the form name from the formid field.

                          ODK Collect on the other hand provides the form name as an
                          id attribute of a tag which follows an <instance> tag.
                        */
                        if (!strncasecmp("form", tag, strlen("form"))) {
                            //	    if (!in_instance) printf("Found start of instance\n");
                            in_instance++;
                        }
                        if ((!strncasecmp("form", &tag[1], strlen("form")))
                            && tag[0] == '/') {
                            in_instance--;
                            //	    if (!in_instance) printf("Found end of instance\n");
                        }
                        if (!in_instance) {
                            // ODK form name appears as attributes of a tag which has a named based
                            // on the name of the form.
                            char name_part[1024];
                            char version_part[1024];
                            int r = 0;
                            if (strlen(tag) < 1024) {
                                r = sscanf(tag, "%s id=\"%[^\"]\" version=\"%[^\"]\"",
                                           exit_tag, name_part, version_part);
                            }
                            if (r == 3) {
                                // Add implied formid tag for ODK forms so that we can more easily find
                                // the recipe that corresponds to a record.
                                fprintf(stderr, "ODK form name is %s.%s\n",
                                        name_part, version_part);
                                int b = snprintf(&stripped[stripped_ofs],
                                                 stripped_size - stripped_ofs, "formid=%s.%s\n",
                                                 name_part, version_part);
                                if (b > 0) stripped_ofs += b;
                                in_instance++;
                            }

                        }
                        if (in_instance && exit_tag[0] && tag[0] == '/' &&
                            !strcasecmp(&tag[1], exit_tag)) {
                            // Found matching tag for the ODK instance opening tag, so end
                            // form instance
                            in_instance--;
                        }
                    } else {
                        if (!strncasecmp(form_name, tag, strlen(form_name))) {
                            in_instance++;
                        }
                        if ((!strncasecmp(form_name, &tag[1], strlen(form_name)))
                            && tag[0] == '/') {
                            in_instance--;
                        }
                    }
                    taglen = 0;
                }
                state = 0;
                break; // out of a tag
            default:
                if (state == 1) {
                    // in a tag specification, so accumulate name of tag
                    if (taglen < sizeof tag) tag[taglen++] = c;
                }
                if (interesting_tag) {
                    // exclude leading spaces from values
                    if (val_len || (c != ' ')) {
                        if (val_len < sizeof value) value[val_len++] = c;
                    }
                }
        }
        c = xml[xmlofs++];
    }
    return stripped_ofs;
}
