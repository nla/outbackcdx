package outbackcdx;

import org.junit.Test;

import outbackcdx.UrlCanonicalizer.ConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;

import static org.junit.Assert.assertEquals;

public class UrlCanonicalizerTest {
    public void t(String source, String expected) throws MalformedURLException, URISyntaxException {
        String canon = UrlCanonicalizer.canonicalize(source);
        assertEquals(expected, canon);
    }

    @Test
    public void test() throws MalformedURLException, URISyntaxException {
        t("http://abr.business.gov.au/(dhj3bi55ekqndn3mjb5myu45)/entityTypeDetails.aspx?SearchText=125", "http://abr.business.gov.au/entitytypedetails.aspx?searchtext=125");
        t("http://www.basix.nsw.gov.au/information/index.jsp;jsessionid=3E544261B39C3B399E1C6BB38D6888E6", "http://basix.nsw.gov.au/information/index.jsp");
        t("http://intersector.wa.gov.au/current_issue?CFID=2051199&CFTOKEN=697395b12ed216e1-F6DFAF77-D433-FA57-5582BC6000844470&jsessionid=92303280691120833351543", "http://intersector.wa.gov.au/current_issue");
        t("http://jobsearch.gov.au/JobDetails/JobDetails.aspx?st=11&WHCode=0&TextOnly=0&rgn=&Occ=7991&BroadLoc=0&SessionID=uqft0ovnt3tq4rrygdt5z145&CommJobs=0&CurPage=4&TotalRec=195&JobPos=65&JobID=107556635&SortDir=1&SortField=3&", "http://jobsearch.gov.au/jobdetails/jobdetails.aspx?broadloc=0&commjobs=0&curpage=4&jobid=107556635&jobpos=65&occ=7991&rgn=&sortdir=1&sortfield=3&st=11&textonly=0&totalrec=195&whcode=0");
        t("http://www.budget.gov.au", "http://budget.gov.au/");
        t("http://thisisthedomainthatneversendsyesitgoesonandonmyfriendsomepeoplestartedtypingitnotknowingwhatitwas.com/", "http://thisisthedomainthatneversendsyesitgoesonandonmyfriendsomepeoplestartedtypingitnotknowingwhatitwas.com/");

        // tests below are based on https://developers.google.com/safe-browsing/developers_guide_v2#Canonicalization
        t("http://%31%36%38%2e%31%38%38%2e%39%39%2e%32%36/%2E%73%65%63%75%72%65/%77%77%77%2E%65%62%61%79%2E%63%6F%6D/", "http://168.188.99.26/.secure/www.ebay.com");
        t("http://host/%25%32%35", "http://host/%25");
        t("http://host/%25%32%35%25%32%35", "http://host/%25%25");
        t("http://host/%2525252525252525", "http://host/%25");
        t("http://host/asdf%25%32%35asd", "http://host/asdf%25asd");
        t("http://host/%%%25%32%35asd%%", "http://host/%25%25%25asd%25%25");
        t("http://host/?%%%25%32%35asd%%", "http://host/?%25%25%25asd%25%25");
        t("http://www.google.com/", "http://google.com/");
        t("http://%31%36%38%2e%31%38%38%2e%39%39%2e%32%36/%2E%73%65%63%75%72%65/%77%77%77%2E%65%62%61%79%2E%63%6F%6D/", "http://168.188.99.26/.secure/www.ebay.com");
        t("http://195.127.0.11/uploads/%20%20%20%20/.verify/.eBaysecure=updateuserdataxplimnbqmn-xplmvalidateinfoswqpcmlx=hgplmcx/", "http://195.127.0.11/uploads/%20%20%20%20/.verify/.ebaysecure=updateuserdataxplimnbqmn-xplmvalidateinfoswqpcmlx=hgplmcx");
        t("http://host%23.com/%257Ea%2521b%2540c%2523d%2524e%25f%255E00%252611%252A22%252833%252944_55%252B", "http://host%23.com/~a!b@c%23d$e%25f^00&11*22(33)44_55+");
        t("http://3279880203/blah", "http://195.127.0.11/blah");
        t("http://www.google.com/blah/..", "http://google.com/");
        t("www.google.com/", "http://google.com/");
        t("www.google.com", "http://google.com/");
        t("http://www.evil.com/blah#frag", "http://evil.com/blah");
        t("http://www.GOOgle.com/", "http://google.com/");
        t("http://www.google.com.../", "http://google.com/");
        t("http://www.google.com/foo\tbar\rbaz\n2", "http://google.com/foobarbaz2");
        t("http://www.google.com/q?r?", "http://google.com/q?r?");
        t("http://www.google.com/q?r?s", "http://google.com/q?r?s");
        t("http://evil.com/foo#bar#baz", "http://evil.com/foo");
        t("http://evil.com/foo;", "http://evil.com/foo;");
        t("http://evil.com/foo?bar;", "http://evil.com/foo?bar;");
        t("http://\u00c0.com/\u00c0", "http://xn--0ca.com/%c3%a0");
        t("http://notrailingslash.com", "http://notrailingslash.com/");
        t("http://www.gotaport.com:1234/", "http://gotaport.com:1234/");
        t("  http://www.google.com/  ", "http://google.com/");
        t("http:// leadingspace.com/", "http://%20leadingspace.com/");
        t("http://%20leadingspace.com/", "http://%20leadingspace.com/");
        t("%20leadingspace.com/", "http://%20leadingspace.com/");
        t("https://www.securesite.com/", "https://securesite.com/");
        t("http://host.com/ab%23cd", "http://host.com/ab%23cd");
        t("http://host.com//twoslashes?more//slashes", "http://host.com/twoslashes?more//slashes");

        t("http://example.org/too/many/../../../dots", "http://example.org/dots");

        UrlCanonicalizer canon = new UrlCanonicalizer();
        assertEquals("au,gov,acma,web)/apservices/action/challenge?method=viewchallenge", canon.surtCanonicalize("http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));

        assertEquals("youtube-dl:au,gov,acma,web)/apservices/action/challenge?method=viewchallenge",
                canon.surtCanonicalize("youtube-dl:http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));
        assertEquals("screenshot:au,gov,acma,web)/apservices/action/challenge?method=viewchallenge",
                canon.surtCanonicalize("screenshot:http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));
        assertEquals("youtube-dl:au,gov,acma,web)/apservices/action/challenge?method=viewchallenge",
                canon.surtCanonicalize("urn:transclusions:http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));
        assertEquals("youtube-dl:00001:au,gov,acma,web)/apservices/action/challenge?method=viewchallenge",
                canon.surtCanonicalize("youtube-dl:00001:http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));
    }

    @Test
    public void pandoraUrlsShouldHaveQueryStringStripped() throws MalformedURLException, URISyntaxException {
        FeatureFlags.setPandoraHacks(true);
        t("http://pandora.nla.gov.au/pan/10075/20150801-0000/www.nlc.org.au/assets/CSS/style62ea.css?ver=1.2", "http://pandora.nla.gov.au/pan/10075/20150801-0000/www.nlc.org.au/assets/css/style62ea.css");
    }

    /*
     * tests our equivalent of this pywb fuzzy match rule
     *
     * - url_prefix: 'com,facebook)/pages_reaction_units/more'
     *   fuzzy_lookup:
     *       - page_id
     *       - cursor
     */
    @Test
    public void testCustomCanon() throws UnsupportedEncodingException, ConfigurationException {
        String yaml
                = "- pattern: com,facebook\\)/pages_reaction_units/more.*?[?&](cursor=[^&]+).*?&(page_id=[^&]+).*\n"
                + "  repl: com,facebook)/pages_reaction_units/more?$2&$1";
        UrlCanonicalizer canon = new UrlCanonicalizer(new ByteArrayInputStream(yaml.getBytes("UTF-8")));

        // fuzzy canon does not apply
        assertEquals("au,gov,acma,web)/apservices/action/challenge?method=viewchallenge", canon.surtCanonicalize("http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));

        // fuzzy canon does apply
        assertEquals("com,facebook)/pages_reaction_units/more?page_id=115681848447769&cursor={\"timeline_cursor\":\"timeline_unit:1:00000000001452384822:04611686018427387904:09223372036854775741:04611686018427387904\",\"timeline_section_cursor\":{},\"has_next_page\":true}", canon.surtCanonicalize("https://www.facebook.com/pages_reaction_units/more/?page_id=115681848447769&cursor=%7B%22timeline_cursor%22%3A%22timeline_unit%3A1%3A00000000001452384822%3A04611686018427387904%3A09223372036854775741%3A04611686018427387904%22%2C%22timeline_section_cursor%22%3A%7B%7D%2C%22has_next_page%22%3Atrue%7D&surface=www_pages_home&unit_count=8&dpr=1&__user=100011276852661&__a=1&__dyn=5V4cjEzUGByK5A9VoWWOGi9Fxrz9EZz8-iWF3ozGFi9LFGA4XG7VKEKGwThEnUF7yWCHAxiESmqaxuqE88HyWDyuipi28gyEnGieKmjBXDmEgF3ebByqAAxaFSifDxCaVQibVojB-qjyVfh6u-exvz8Gicx2jCoO8hqwzxmmayrhbAyFUSibBDCyVF88GxrUCaC-Rx2Qh1Gcy8C6rld13xivQFfxqHu4olDh4dy8gyVUkCyFFFK5p8BaUhKHWG4ui9K8nVQGmV7Gh6BJq8G8WDDio8lfBGq9gZ4jyXV98-8t2eZKqaHyoO5pEpJaroK8Fp8HZ3aXAnAz4&__af=h0&__req=1k&__be=-1&__pc=PHASED%3ADEFAULT&__rev=3237777&__spin_r=3237777&__spin_b=trunk&__spin_t=1503096999"));
    }
}
