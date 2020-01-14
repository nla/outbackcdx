package outbackcdx;

import org.junit.Before;
import org.junit.Test;

import outbackcdx.UrlCanonicalizer.ConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.util.Arrays;
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


    private UrlCanonicalizer fuzzCanon;
    @Before
    public void initFuzz() throws UnsupportedEncodingException, ConfigurationException {
        String yaml =
                "rules:\n" +
                "- url_prefix: 'com,twitter)/i/profiles/show/'\n" +
                "  fuzzy_lookup: '/profiles/show/.*with_replies\\?.*(max_id=[^&]+)'\n" +
                "- url_prefix: 'com,twitter)/i/timeline'\n" +
                "  fuzzy_lookup:\n" +
                "  - max_position\n" +
                "  - include_entities\n" +
                "- url_prefix: 'com,facebook)/ajax/pagelet/generic.php/photoviewerpagelet'\n" +
                "  fuzzy_lookup:\n" +
                "    match: '(\"(?:cursor|cursorindex)\":[\"\\d\\w]+)'\n" +
                "    find_all: true\n" +
                "- url_prefix: 'com,staticflickr,'\n" +
                "  fuzzy_lookup:\n" +
                "    match: '([0-9]+_[a-z0-9]+).*?.jpg'\n" +
                "    replace: '/'\n" +
                "    # replace: 'staticflickr,'\n" +
                "- url_prefix: ['com,yimg,l)/g/combo', 'com,yimg,s)/pw/combo', 'com,yahooapis,yui)/combo']\n" +
                "  fuzzy_lookup: '([^/]+(?:\\.css|\\.js))'\n" +
                "- url_prefix: 'com,vimeo,av)/'\n" +
                "  # only use non query part of url, ignore query\n" +
                "  fuzzy_lookup: '()'\n" +
                "- url_prefix: 'com,googlevideo,'\n" +
                "  fuzzy_lookup:\n" +
                "    match:\n" +
                "      regex: 'com,googlevideo.*/videoplayback.*'\n" +
                "      args:\n" +
                "      - id\n" +
                "      - itag\n" +
                "      #- mime\n" +
                "    filter:\n" +
                "    - 'urlkey:{0}'\n" +
                "    - '!mimetype:text/plain'\n" +
                "    type: 'domain'\n" +
                "- url_prefix: com,example,zuh)/\n" +
                "  fuzzy_lookup: '[&?](?:.*)'\n" +
                "";

        fuzzCanon = new UrlCanonicalizer(new ByteArrayInputStream(yaml.getBytes("UTF-8")));
    }

    @Test
    public void testFuzzCanon() {
        // fuzzy canon does not apply
        assertEquals(
                "au,gov,acma,web)/apservices/action/challenge?method=viewchallenge",
                fuzzCanon.surtCanonicalize("http://web.acma.gov.au/apservices/action/challenge?method=viewChallenge"));

        // fuzzy canon does apply
        assertEquals(
                fuzzCanon.surtCanonicalize("https://twitter.com/i/profiles/show/09Valenti/timeline/with_replies?include_available_features=1&include_entities=1&max_id=388760995968974848"),
                "fuzzy:com,twitter)/i/profiles/show/09valenti/timeline/with_replies?max_id=388760995968974848");

        assertEquals(
                fuzzCanon.surtCanonicalize("https://twitter.com/i/timeline?include_available_features=1&include_entities=1&max_position=1000044390125944832&reset_error_state=false"),
                "fuzzy:com,twitter)/i/timeline?include_entities=1&max_position=1000044390125944832");

        assertEquals(
                fuzzCanon.surtCanonicalize("https://www.facebook.com/ajax/pagelet/generic.php/PhotoViewerPagelet?fb_dtsg_ag&ajaxpipe=1&ajaxpipe_token=AXhc7hWnFHK7VBPx&no_script_path=1&data=%7B%22cursor%22%3A%221296369020399142%22%2C%22version%22%3A6%2C%22end%22%3A%22962309407138440%22%2C%22fetchSize%22%3A-12%2C%22opaqueCursor%22%3Anull%2C%22tagSuggestionMode%22%3A%22everyone%22%2C%22is_from_groups%22%3Afalse%2C%22set%22%3A%22a.540565829312802%22%2C%22type%22%3A3%2C%22total%22%3A14%2C%22cursorIndex%22%3A0%7D&__user=0&__a=1&__dyn=7AzHJ4zamaWxd2umeCExWyC5UOqfoOm9AKGgS8WGnJ4WqF1eU8Eqzob4q6oF4GbGqKi5azppEHoOqqGxSaUyGxeipi28gyElWAAzppenKtqx2AcUK4F98iGvxifGcgLAKibzUKmih4-vAZ4zogxu9AyAUOKbzAaUx5G3Cm4bKm8yFVpV8hyQdzUmDm495UO4KK4bh4u4pFEixbAJkUGrz9-vix6dGTx67kmdz8ObDK9y98GqfxG9hFEGWBBKunAxpu9iTy7GiumoWExFSkK25h8iyXy998KUKA5oOmKFKbUC13x3ximfALhaJeSh3oCi9lkRyWyV8V3kdz9eaDCxKQECiq9jgW5Abzp49BUFoN4KmbDzAUnyHxqKuiaz9qAm2DGEky44bAAxmqumHAK8J6Kh7x6cyU998BfgKUKu7AUyh4AFeWXK7pby9qGFEG8DAKq8gCVQqazWgK5FEOiEEwjihbCAyU&__req=jsonp_3&__be=0&__pc=PHASED%3ADEFAULT&dpr=1&__rev=4655486&__adt=3"),
                "fuzzy:com,facebook)/ajax/pagelet/generic.php/photoviewerpagelet?\"cursor\":\"1296369020399142\"&\"cursorindex\":0");

        assertEquals(
                fuzzCanon.surtCanonicalize("https://bf1-farm2.staticflickr.com/1907/30471641737_4378b23f76_b.jpg"),
                fuzzCanon.surtCanonicalize("https://bf1-farm2.staticflickr.com/1907/30471641737_4378b23f76_z.jpg?zz=1"));
        assertEquals(
                fuzzCanon.surtCanonicalize("https://bf1-farm2.staticflickr.com/1907/30471641737_4378b23f76_b.jpg"),
                "fuzzy:com,staticflickr,bf1-farm2)/30471641737_4378b23f76");

        assertEquals(
                fuzzCanon.surtCanonicalize("http://l.yimg.com/g/combo/1/3.40770.css"),
                fuzzCanon.surtCanonicalize("http://l.yimg.com/g/combo/1/3.40770.css?c/c_.J_nav.BC.vX3Ui&c/c_.J_.D.BC.vWpVt&c/c_.J_.D.BC.vWpVt&c/c_.EM_.D.BC.vW6Ji&c/c_.FW-.HN.BC."));
        assertEquals(
                fuzzCanon.surtCanonicalize("http://l.yimg.com/g/combo/1/3.40770.css?c/c_.J_nav.BC.vX3Ui&c/c_.J_.D.BC.vWpVt&c/c_.J_.D.BC.vWpVt&c/c_.EM_.D.BC.vW6Ji&c/c_.FW-.HN.BC."),
                "fuzzy:com,yimg,l)/g/combo/1/3.40770.css?3.40770.css");

        assertEquals(
                fuzzCanon.surtCanonicalize("https://s.yimg.com/pw/combo/1/3.11.0?autocomplete-list/assets/skins/sam/autocomplete-list.css&c/c_.HO-3.BC.v223Nz&c/c_.JQ.BC.v25xKg"),
                fuzzCanon.surtCanonicalize("https://s.yimg.com/pw/combo/1/3.11.0?autocomplete-list/assets/skins/sam/autocomplete-list.css&c/c_.HO-3.BC.v4cQs5o6&c/c_.JQ.BC.v3m1XDji"));
        assertEquals(
                fuzzCanon.surtCanonicalize("https://s.yimg.com/pw/combo/1/3.11.0?autocomplete-list/assets/skins/sam/autocomplete-list.css&c/c_.HO-3.BC.v223Nz&c/c_.JQ.BC.v25xKg"),
                "fuzzy:com,yimg,s)/pw/combo/1/3.11.0?autocomplete-list.css");

        assertEquals(
                fuzzCanon.surtCanonicalize("http://yui.yahooapis.com/combo?2.8.2/build/logger/assets/skins/sam/logger.css"),
                fuzzCanon.surtCanonicalize("http://yui.yahooapis.com/combo?2.8.2r1/build/logger/assets/skins/sam/logger.css"));
        assertEquals(
                fuzzCanon.surtCanonicalize("http://yui.yahooapis.com/combo?2.8.2/build/logger/assets/skins/sam/logger.css"),
                "fuzzy:com,yahooapis,yui)/combo?logger.css");

        assertEquals(
                fuzzCanon.surtCanonicalize("http://av.vimeo.com/69311/481/44350578.mp4?token2=1383828275_643035b604bc4e836bd702cd28bab94c&aksessionid=41a52d830713bc2c&ns=4"),
                fuzzCanon.surtCanonicalize("http://av.vimeo.com/69311/481/44350578.mp4?token2=1384356471_1486adf88d97f41b97cc73c029de6696&aksessionid=2b4f6c2c90b4f1f4&ns=4"));
        assertEquals(
                fuzzCanon.surtCanonicalize("http://av.vimeo.com/69311/481/44350578.mp4?token2=1383828275_643035b604bc4e836bd702cd28bab94c&aksessionid=41a52d830713bc2c&ns=4"),
                "fuzzy:com,vimeo,av)/69311/481/44350578.mp4?");

        assertEquals(
                fuzzCanon.surtCanonicalize("http://o-o.preferred.nuq04t11.v3.cache1.googlevideo.com/videoplayback?id=1c98fe7da5ffb404&itag=5&app=blogger&ip=0.0.0.0&ipbits=0&expire=1335344084&sparams=id,itag,ip,ipbits,expire&signature=5371654FF54A9C169F2F42334235D096F41053A7.448A800D1DED819ED5C476E29BA69F38FEE48B26&key=ck1&redirect_counter=2&cms_options=map=ts_be&cms_redirect=yes"),
                fuzzCanon.surtCanonicalize("http://tc.v3.cache1.googlevideo.com/videoplayback?id=1c98fe7da5ffb404&itag=5&app=blogger&ip=0.0.0.0&ipbits=0&expire=1335344878&sparams=id,itag,ip,ipbits,expire&signature=48F65E282E7965BDC97DD331CE25D851FB38C9D3.2DA7A8DC8FC4207F6EAD532CBE0E1AF4DB73317C&key=ck1&redirect_counter=1"));
        assertEquals(
                fuzzCanon.surtCanonicalize("http://o-o.preferred.nuq04t11.v3.cache1.googlevideo.com/videoplayback?id=1c98fe7da5ffb404&itag=5&app=blogger&ip=0.0.0.0&ipbits=0&expire=1335344084&sparams=id,itag,ip,ipbits,expire&signature=5371654FF54A9C169F2F42334235D096F41053A7.448A800D1DED819ED5C476E29BA69F38FEE48B26&key=ck1&redirect_counter=2&cms_options=map=ts_be&cms_redirect=yes"),
                "fuzzy:com,googlevideo,?id=1c98fe7da5ffb404&itag=5");

        assertEquals(
                fuzzCanon.surtCanonicalize("http://zuh.example.com/?some=query&params"),
                fuzzCanon.surtCanonicalize("http://zuh.example.com/?some=other&query=params"));
        assertEquals(
                fuzzCanon.surtCanonicalize("http://zuh.example.com/?some=query&params"),
                "fuzzy:com,example,zuh)/?");
    }

    @Test
    public void testFuzzConfig() {
        assertEquals(fuzzCanon.fuzzyRules.size(), 8);

        assertEquals(fuzzCanon.fuzzyRules.get(0).urlPrefixes, Arrays.asList("com,twitter)/i/profiles/show/"));
        assertEquals(fuzzCanon.fuzzyRules.get(0).pattern.pattern(), "/profiles/show/.*with_replies\\?.*(max_id=[^&]+)");
        assertEquals(fuzzCanon.fuzzyRules.get(0).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(0).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(0).isDomain, false);

        assertEquals(fuzzCanon.fuzzyRules.get(1).urlPrefixes, Arrays.asList("com,twitter)/i/timeline"));
        assertEquals(fuzzCanon.fuzzyRules.get(1).pattern.pattern(), "[?&](\\Qinclude_entities\\E=[^&]+).*[?&](\\Qmax_position\\E=[^&]+)");
        assertEquals(fuzzCanon.fuzzyRules.get(1).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(1).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(1).isDomain, false);

        assertEquals(fuzzCanon.fuzzyRules.get(2).urlPrefixes, Arrays.asList("com,facebook)/ajax/pagelet/generic.php/photoviewerpagelet"));
        assertEquals(fuzzCanon.fuzzyRules.get(2).pattern.pattern(), "(\"(?:cursor|cursorindex)\":[\"\\d\\w]+)");
        assertEquals(fuzzCanon.fuzzyRules.get(2).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(2).findAll, true);
        assertEquals(fuzzCanon.fuzzyRules.get(2).isDomain, false);

        assertEquals(fuzzCanon.fuzzyRules.get(3).urlPrefixes, Arrays.asList("com,staticflickr,"));
        assertEquals(fuzzCanon.fuzzyRules.get(3).pattern.pattern(), "([0-9]+_[a-z0-9]+).*?.jpg");
        assertEquals(fuzzCanon.fuzzyRules.get(3).replaceAfter, "/");
        assertEquals(fuzzCanon.fuzzyRules.get(3).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(3).isDomain, false);

        assertEquals(fuzzCanon.fuzzyRules.get(4).urlPrefixes, Arrays.asList("com,yimg,l)/g/combo", "com,yimg,s)/pw/combo", "com,yahooapis,yui)/combo"));
        assertEquals(fuzzCanon.fuzzyRules.get(4).pattern.pattern(), "([^/]+(?:\\.css|\\.js))");
        assertEquals(fuzzCanon.fuzzyRules.get(4).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(4).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(4).isDomain, false);

        assertEquals(fuzzCanon.fuzzyRules.get(5).urlPrefixes, Arrays.asList("com,vimeo,av)/"));
        assertEquals(fuzzCanon.fuzzyRules.get(5).pattern.pattern(), "()");
        assertEquals(fuzzCanon.fuzzyRules.get(5).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(5).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(5).isDomain, false);

        assertEquals(fuzzCanon.fuzzyRules.get(6).urlPrefixes, Arrays.asList("com,googlevideo,"));
        assertEquals(fuzzCanon.fuzzyRules.get(6).pattern.pattern(), "com,googlevideo.*/videoplayback.*[?&](\\Qid\\E=[^&]+).*[?&](\\Qitag\\E=[^&]+)");
        assertEquals(fuzzCanon.fuzzyRules.get(6).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(6).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(6).isDomain, true);

        assertEquals(fuzzCanon.fuzzyRules.get(7).urlPrefixes, Arrays.asList("com,example,zuh)/"));
        assertEquals(fuzzCanon.fuzzyRules.get(7).pattern.pattern(), "[&?](?:.*)");
        assertEquals(fuzzCanon.fuzzyRules.get(7).replaceAfter, "?");
        assertEquals(fuzzCanon.fuzzyRules.get(7).findAll, false);
        assertEquals(fuzzCanon.fuzzyRules.get(7).isDomain, false);
    }
}
