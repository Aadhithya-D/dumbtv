package tv.dumbtv.home;

/** Host-side checks for Zee5's links and show ids, without a device or test framework. */
public final class Zee5Test {
    private static void check(boolean ok, String name) {
        if (!ok) throw new AssertionError(name);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    public static void main(String[] args) {
        String banner = "https://akamaividz2.zee5.com/image/upload/w_1920,q_auto:eco,c_fit,f_webp/resources/0-6-4z51084572/tv_banner/3840x2160tvbannere7d6";
        String cover = "https://akamaividz2.zee5.com/image/upload/w_1920,q_auto:eco,c_fit,f_webp/resources/0-6-4z51084572/app_cover/";

        check(eq(Zee5.link("0-1-6z51096053", banner), "https://www.zee5.com/tv-shows/details/show/0-6-4z51084572"),
                "an episode links to its show's page");
        check(eq(Zee5.link("0-6-4z51084572", null), "https://www.zee5.com/tv-shows/details/show/0-6-4z51084572"),
                "a show links to its page");
        check(eq(Zee5.link("0-0-1z5123", null), "https://www.zee5.com/movies/details/title/0-0-1z5123"),
                "a film links to its title page");
        check(Zee5.link("0-1-6z51096053", null) == null, "an episode without a picture has no link");
        check(Zee5.link(null, banner) == null, "no id, no link");

        check(eq(Zee5.show(null, banner), "0-6-4z51084572"), "show id from a tv_banner picture");
        check(eq(Zee5.show(null, cover), "0-6-4z51084572"), "show id from an app_cover picture");
        check(eq(Zee5.show("https://www.zee5.com/tv-shows/details/show/0-6-4z51084572/episode/0-1-6z51096053", null),
                "0-6-4z51084572"), "show id from an older episode link");
        check(eq(Zee5.show(Zee5.link("0-1-6z51096053", banner), null), "0-6-4z51084572"), "show id from a link");
        check(Zee5.show("https://www.zee5.com/movies/details/title/0-0-1z5123", null) == null, "a film has no show");
        check(Zee5.show("", "") == null, "empty link and picture have no show");
        check(eq(Zee5.nameFromAddress("https://www.zee5.com/tv-sa-re-ga-ma-pa-2026s/details/sa-re-ga-ma-pa-2026/0-6-4z51084572"),
                "Sa Re Ga Ma Pa 2026"), "the show's name from its redirect address");
        check(Zee5.nameFromAddress("https://www.zee5.com/") == null, "an address without a show has no name");
        check(Zee5.nameFromAddress(null) == null, "no address, no name");
        System.out.println("Zee5Test: ok");
    }
}
