#import <AppKit/AppKit.h>
#import <objc/runtime.h>
#import "HideTestWindows.h"

// UI tests order real windows front to lay them out. Fully transparent windows keep
// visibility, key status and rendering for assertions without flashing on screen.
// Set OPENDICTATE_SHOW_TEST_WINDOWS=1 to watch them.

// Matches init semantics under ARC: self is consumed and the result is returned retained.
typedef id (*WindowInit)(__attribute__((ns_consumed)) id, SEL, NSRect, NSWindowStyleMask, NSBackingStoreType, BOOL) NS_RETURNS_RETAINED;
static WindowInit originalInit;

static id transparentInit(__attribute__((ns_consumed)) id self, SEL command, NSRect rect, NSWindowStyleMask style,
                          NSBackingStoreType backing, BOOL defer) NS_RETURNS_RETAINED {
    NSWindow *window = originalInit(self, command, rect, style, backing, defer);
    window.alphaValue = 0;
    return window;
}

__attribute__((constructor)) static void hideTestWindowsOnLoad(void) {
    if (getenv("OPENDICTATE_SHOW_TEST_WINDOWS") != NULL) { return; }
    Method method = class_getInstanceMethod(NSWindow.class, @selector(initWithContentRect:styleMask:backing:defer:));
    originalInit = (WindowInit)method_setImplementation(method, (IMP)transparentInit);
}

BOOL ODTestWindowsHidden(void) { return originalInit != NULL; }
