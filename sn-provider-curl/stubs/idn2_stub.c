// idn2 stub — satisfies @link("idn2") from sttp.
// Real idn2 symbols are statically linked inside libcurl from curl-natives.
// No headers — cross-compiled for 6 platforms from macOS.

int idn2_to_ascii_8z(const char *input, char **output, int flags) {
    (void)flags;
    if (output) *output = 0;
    return 0;
}

const char *idn2_strerror(int rc) {
    (void)rc;
    return "stub";
}

void idn2_free(void *ptr) {
    (void)ptr;
}

int idn2_lookup_u8(const void *src, void *dest, int flags) {
    (void)src; (void)dest; (void)flags;
    return 0;
}
