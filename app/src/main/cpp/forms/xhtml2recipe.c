/*
  (C) Paul Gardner-Stephen 2012-5.
  * 
  * CREATE specification stripped file from a Magpi XHTML form
  * Generate .recipe and .template files
  */

/*
  Copyright (C) 2012-5 Paul Gardner-Stephen
  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License
  as published by the Free Software Foundation; either version 2
  of the License, or (at your option) any later version.
  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
  GNU General Public License for more details.
  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
*/
#include <stdio.h>
#include <string.h>
#include <ctype.h>
#include "../expat/expat.h"

#ifdef ANDROID

#include <android/log.h>

#define LOGI(X, ...) ((void)__android_log_print(ANDROID_LOG_INFO, "xhtml", X, ##__VA_ARGS__))
#define LOGE(X, ...) ((void)__android_log_print(ANDROID_LOG_ERROR, "xhtml", X, ##__VA_ARGS__))

#else

#define LOGI(X, ...) ((void)fprintf(stderr, X "\n", ##__VA_ARGS__))
#define LOGE(X, ...) ((void)fprintf(stderr, X "\n", ##__VA_ARGS__))

#endif

const char *implied_meta_fields =
        "userid:string:0:0:0\n"
                "accesstoken:string:0:0:0\n"
                "formid:string:0:0:0\n"
                "lastsubmittime:magpitimestamp:0:0:0\n"
                "endrecordtime:magpitimestamp:0:0:0\n"
                "startrecordtime:magpitimestamp:0:0:0\n"
                "version:string:0:0:0\n"
                "uuid:magpiuuid:0:0:0\n"
                "latitude:float:-90:90:0\n"
                "longitude:float:-200:200:0\n";
const char *implied_meta_fields_template =
        "<userid>$userid$</userid>\n"
                "<accesstoken>$accesstoken$</accesstoken>\n"
                "<formid>$formid$</formid>\n"
                "<lastsubmittime>$lastsubmittime$</lastsubmittime>\n"
                "<endrecordtime>$endrecordtime$</endrecordtime>\n"
                "<startrecordtime>$startrecordtime$</startrecordtime>\n"
                "<version>$version$</version>\n"
                "<uuid>$uuid$</uuid>\n"
                "<geotag>\n"
                "  <longitude>$longitude$</longitude>\n"
                "  <latitude>$latitude$</latitude>\n"
                "</geotag>\n";

static char *strgrow(char *in, char *new) {
    char *out = malloc(strlen(in) + strlen(new) + 1);
    snprintf(out, strlen(in) + strlen(new) + 1, "%s%s", in, new);
    free(in);
    return out;
}

#define MIN(a, b) (((a)<(b))?(a):(b))
#define MAX(a, b) (((a)>(b))?(a):(b))

//TODO 30.05.2014 	: Handle if there are two fields with same name
//		            : Add tests
//		            : Improve constraints work
//
//Creation specification stripped file from ODK XML
//FieldName:Type:Minimum:Maximum:Precision,Select1,Select2,...,SelectN

struct parse_state {
    char *xhtmlFormName;
    char *xhtmlFormVersion;

    char *xhtml2template[1024];
    int xhtml2templateLen;
    char *xhtml2recipe[1024];
    int xhtml2recipeLen;

    int xhtml_in_instance;

    char *selects[1024];
    int xhtmlSelectsLen;
    char *xhtmlSelectElem;
    int xhtmlSelectFirst;
    int xhtml_in_value;
};

static const char *SELECT1 = "select1";

//This function is called  by the XML library each time it sees a new tag
static void start_xhtml(void *data, const char *el, const char **attr)
{
    const char *node_name = "", *node_type = "", *node_constraint = "";
    int i;
    struct parse_state *state = data;
    char temp[1024];

    if (state->xhtml_in_instance) { // We are between <instance> tags, so we want to get everything to create template file
        char *str = calloc(4096, sizeof(char));
        strcpy(str, "<");
        strcat(str, el);
        for (i = 0; attr[i]; i += 2) {
            strcat(str, " ");
            strcat(str, attr[i]);
            strcat(str, "=\"");
            strcat(str, attr[i + 1]);
            strcat(str, "\"");
        }
        strcat(str, ">");
        strcat(str, "$");
        strcat(str, el);
        strcat(str, "$");
        state->xhtml2template[state->xhtml2templateLen++] = str;
    }

        //Looking for bind elements to create the recipe file
    else if ((!strcasecmp("bind", el)) || (!strcasecmp("xf:bind", el))) {
        for (i = 0; attr[i]; i += 2) //Found a bind element, look for attributes
        {
            //Looking for attribute nodeset
            if (!strncasecmp("nodeset", attr[i], strlen("nodeset"))) {
                node_name = strrchr(attr[i + 1], '/') + 1;
            }

            //Looking for attribute type
            if (!strncasecmp("type", attr[i], strlen("type"))) {
                if (!strcasecmp(attr[i + 1], "xsd:dropdown")) {
                    // Dropdown is a synonym for select1 (multiple choice)
                    node_type = SELECT1;
                } else if (!strcasecmp(attr[i + 1], "xsd:radio")) {
                    // So is radio
                    node_type = SELECT1;
                } else if (!strcasecmp(attr[i + 1], "xsd:checkbox")) {
                    // ... and checkbox
                    node_type = SELECT1;
                } else {
                    const char *attribute = attr[i + 1];
                    // Skip "XXX:" prefixes on types
                    if (strstr(attribute, ":"))
                        attribute = strstr(attribute, ":") + 1;
                    node_type = attribute;
                }
            }

            //Looking for attribute constraint
            if (!strncasecmp("constraint", attr[i], strlen("constraint"))) {
                node_constraint = attr[i + 1];
            }
        }

        //Now we got node_name, node_type, node_constraint
        //Lets build output
        LOGI("Parsing field %s:%s", node_name, node_type);

        // Select, special case we need to wait later to get all informations (ie the range)
        if ((!strcasecmp(node_type, "select")) || (!strcasecmp(node_type, SELECT1)))
        {
            snprintf(temp, sizeof temp, "%s:enum:0:0:0:", node_name);
            state->selects[state->xhtmlSelectsLen] = strdup(temp);
            state->xhtmlSelectsLen++;
        } else if ((!strcasecmp(node_type, "decimal"))
                   || (!strcasecmp(node_type, "integer"))
                   || (!strcasecmp(node_type, "int"))) // Integers and decimal
        {
            LOGI("Parsing INT field %s:%s", node_name, node_type);
            snprintf(temp, sizeof temp, "%s:%s", node_name, node_type);
            state->xhtml2recipe[state->xhtml2recipeLen] = strdup(temp);

            size_t constrain_len = strlen(node_constraint);

            if (constrain_len) {
                const char *ptr = node_constraint;
                int a, b;

                //We look for 0 to 2 digits
                while (!isdigit(*ptr) && (ptr < node_constraint + constrain_len)) ptr++;
                a = atoi(ptr);
                while (isdigit(*ptr) && (ptr < node_constraint + constrain_len)) ptr++;
                while (!isdigit(*ptr) && (ptr < node_constraint + constrain_len)) ptr++;
                b = atoi(ptr);
                if (b <= a) b = a + 999;
                snprintf(temp, sizeof temp, "%s:%s:%d:%d:0", node_name, node_type, MIN(a, b), MAX(a, b));
                LOGI("%s", temp);
                free(state->xhtml2recipe[state->xhtml2recipeLen]);
                state->xhtml2recipe[state->xhtml2recipeLen] = strdup(temp);

            } else {
                // Default to integers being in the range 0 to 999.
                snprintf(temp, sizeof temp, "%s:%s:0:999:0", node_name, node_type);
                free(state->xhtml2recipe[state->xhtml2recipeLen]);
                state->xhtml2recipe[state->xhtml2recipeLen] = strdup(temp);
            }
            state->xhtml2recipeLen++;

        }
            // All others type except binary (ignore binary fields in succinct data)
        else if (strcasecmp(node_type, "binary"))
        {
            if (!strcasecmp(node_name, "instanceID")) {
                snprintf(temp, sizeof temp, "%s:uuid", node_name);
                state->xhtml2recipe[state->xhtml2recipeLen] = strdup(temp);
            } else {
                LOGI("xhtml2recipeLen = %d", state->xhtml2recipeLen);

                snprintf(temp, 1024, "%s:%s", node_name, node_type);
                state->xhtml2recipe[state->xhtml2recipeLen] = strdup(temp);
            }
            snprintf(temp, sizeof temp, "%s:0:0:0", state->xhtml2recipe[state->xhtml2recipeLen]);
            free(state->xhtml2recipe[state->xhtml2recipeLen]);
            state->xhtml2recipe[state->xhtml2recipeLen] = strdup(temp);
            state->xhtml2recipeLen++;
        }
    }

        //Now look for selects specifications, we wait until to find a select node
    else if ((!strcasecmp("xf:select1", el)) || (!strcasecmp("xf:select", el))) {
        for (i = 0; attr[i]; i += 2) //Found a select element, look for attributes
        {
            if (!strcasecmp("bind", attr[i])) {
                const char *last_slash = strrchr(attr[i + 1], '/');
                // Allow for non path-indicated bindings in XHTML forms
                if (!last_slash) last_slash = attr[i + 1]; else last_slash++;
                LOGI("Found multiple-choice selection definition '%s'", last_slash);
                if (state->xhtmlSelectElem)
                    free(state->xhtmlSelectElem);
                state->xhtmlSelectElem = strdup(last_slash);
                state->xhtmlSelectFirst = 1;
            }
        }
    }

        //We are in a select node and we need to find a value element
    else if ((state->xhtmlSelectElem) && ((!strcasecmp("value", el)) || (!strcasecmp("xf:value", el)))) {
        state->xhtml_in_value = 1;
    }

        //We reached the start of the data in the instance, so start collecting fields
    else if (!strcasecmp("data", el)) {
        state->xhtml_in_instance = 1;
    } else if (!strcasecmp("xf:model", el)) {
        // Form name is the id attribute of the xf:model tag
        for (i = 0; attr[i]; i += 2) {
            if (!strcasecmp("id", attr[i])) {
                if (state->xhtmlFormName)
                    free(state->xhtmlFormName);
                state->xhtmlFormName = strdup(attr[i + 1]);
            }
            if (!strcasecmp("dd:formid", attr[i])) {
                if (state->xhtmlFormVersion)
                    free(state->xhtmlFormVersion);
                state->xhtmlFormVersion = strdup(attr[i + 1]);
            }
        }
    }
}

static void characterdata_xhtml(void *data, const char *el, int len)
//This function is called  by the XML library each time we got data in a tag
{
    int i;
    struct parse_state *state = data;


    if (state->xhtmlSelectElem && state->xhtml_in_value) {
        char x[len + 2]; //el is not null terminated, so copy it to x and add \0
        memcpy(x, el, len);
        memcpy(x + len, "", 1);

        for (i = 0; i < state->xhtmlSelectsLen; i++) {
            if (!strncasecmp(state->xhtmlSelectElem, state->selects[i], strlen(state->xhtmlSelectElem))) {
                if (state->xhtmlSelectFirst) {
                    state->xhtmlSelectFirst = 0;
                } else {
                    state->selects[i] = strgrow(state->selects[i], ",");
                }
                state->selects[i] = strgrow(state->selects[i], x);
            }
        }
    }
}

//This function is called  by the XML library each time it sees an ending of a tag
static void end_xhtml(void *data, const char *el)
{
    struct parse_state *state = data;

    if (state->xhtmlSelectElem && ((!strcasecmp("xf:select1", el)) || (!strcasecmp("xf:select", el)))) {
        free(state->xhtmlSelectElem);
        state->xhtmlSelectElem = NULL;
    }

    if (state->xhtml_in_value && ((!strcasecmp("value", el)) || (!strcasecmp("xf:value", el)))) {
        state->xhtml_in_value = 0;
    }

    if (state->xhtml_in_instance && (!strcasecmp("data", el))) {
        state->xhtml_in_instance = 0;
    }

    if (state->xhtml_in_instance) { // We are between <instance> tags, we want to get everything
        char *str = calloc(4096, sizeof(char *));
        strcpy(str, "</");
        strcat(str, el);
        strcat(str, ">\n");
        state->xhtml2template[state->xhtml2templateLen++] = str;
    }
}

static int appendto(char *out,size_t *used,size_t max,char *stuff)
{
    int l = strlen(stuff);
    if (((*used)+l)>=max) return -1;
    strcpy(&out[*used],stuff);
    *used+=l;
    return 0;
}

int xhtmlToRecipe(const char *xmltext,
                  char *formname, size_t name_len,
                  char *formversion, size_t version_len,
                  char *recipetext, size_t *recipeLen,
                  char *templatetext, size_t *templateLen) {
    struct parse_state state;
    XML_Parser parser;
    int i;
    int ret = -1;

    //ParserCreation
    parser = XML_ParserCreate(NULL);
    if (parser == NULL) {
        LOGE("ERROR: %s: Parser not created", __FUNCTION__);
        return -1;
    }

    memset(&state, 0, sizeof state);
    state.xhtmlSelectFirst = 1;

    XML_SetUserData(parser, &state);

    // Tell expat to use functions start() and end() each times it encounters the start or end of an element.
    XML_SetElementHandler(parser, start_xhtml, end_xhtml);
    // Tell expat to use function characterData()
    XML_SetCharacterDataHandler(parser, characterdata_xhtml);

    LOGI("About to call XML_Parse()");

    //Parse Xml Text
    if (XML_Parse(parser, xmltext, strlen(xmltext), XML_TRUE) ==
        XML_STATUS_ERROR) {
        LOGE("ERROR: %s: Cannot parse, file may be too large or not well-formed XML",
                __FUNCTION__);
        goto cleanup;
    }

    LOGI("XML_Parse() succeeded, xhtml2recipeLen = %d", state.xhtml2recipeLen);

    if (recipetext){
        // Build recipe output
        size_t recipeMaxLen = *recipeLen;

        // Start with implied fields
        strcpy(recipetext, implied_meta_fields);
        *recipeLen = strlen(recipetext);

        // Now add explicit fields
        for (i = 0; i < state.xhtml2recipeLen; i++) {
            if (appendto(recipetext, recipeLen, recipeMaxLen, state.xhtml2recipe[i])) {
                LOGE("ERROR: %s:%d: %s() recipe text overflow.",
                        __FILE__, __LINE__, __FUNCTION__);
                goto cleanup;
            }
            if (appendto(recipetext, recipeLen, recipeMaxLen, "\n")) {
                LOGE("ERROR: %s:%d: %s() recipe text overflow.",
                        __FILE__, __LINE__, __FUNCTION__);
                goto cleanup;
            }
        }
        for (i = 0; i < state.xhtmlSelectsLen; i++) {
            if (appendto(recipetext, recipeLen, recipeMaxLen, state.selects[i])) {
                LOGE("ERROR: %s:%d: %s() recipe text overflow.",
                        __FILE__, __LINE__, __FUNCTION__);
                goto cleanup;
            }
            if (appendto(recipetext, recipeLen, recipeMaxLen, "\n")) {
                LOGE("ERROR: %s:%d: %s() recipe text overflow,",
                        __FILE__, __LINE__, __FUNCTION__);
                goto cleanup;
            }
        }
    }

    if (templatetext){
        size_t templateMaxLen = *templateLen;
        *templateLen = 0;
        for (i = 0; i < state.xhtml2templateLen; i++) {
            if (appendto(templatetext, templateLen, templateMaxLen, state.xhtml2template[i])) {
                LOGE("ERROR: %s:%d: %s() template text overflow.",
                        __FILE__, __LINE__, __FUNCTION__);
                goto cleanup;
            }
        }
    }

    if (formname)
        snprintf(formname, name_len, "%s", state.xhtmlFormName ? state.xhtmlFormName : "");
    if (formversion)
        snprintf(formversion, version_len, "%s", state.xhtmlFormVersion ? state.xhtmlFormVersion : "");
    LOGI("xhtmlToRecipe(): formname='%s'", state.xhtmlFormName);
    LOGI("xhtmlToRecipe(): formversion='%s'", state.xhtmlFormVersion);
    ret = 0;

cleanup:

    if (state.xhtmlFormName)
        free(state.xhtmlFormName);
    if (state.xhtmlFormVersion)
        free(state.xhtmlFormVersion);
    for (i = 0; i < state.xhtml2recipeLen; i++)
        free(state.xhtml2recipe[i]);
    for (i = 0; i < state.xhtmlSelectsLen; i++)
        free(state.selects[i]);
    for (i = 0; i < state.xhtml2templateLen; i++)
        free(state.xhtml2template[i]);

    XML_ParserFree(parser);
    LOGI("xhtmlFormName=%s, xhtmlFormVersion=%s", state.xhtmlFormName, state.xhtmlFormVersion);

    return (ret);
}
