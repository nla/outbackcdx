package tinycdxserver;

import org.junit.Test;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

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

        // tests below are based on https://developers.google.com/safe-browsing/developers_guide_v2#Canonicalization
        t("http://%31%36%38%2e%31%38%38%2e%39%39%2e%32%36/%2E%73%65%63%75%72%65/%77%77%77%2E%65%62%61%79%2E%63%6F%6D/", "http://168.188.99.26/.secure/www.ebay.com");
        t("http://host/%25%32%35", "http://host/%25");
        t("http://host/%25%32%35%25%32%35", "http://host/%25%25");
        t("http://host/%2525252525252525", "http://host/%25");
        t("http://host/asdf%25%32%35asd", "http://host/asdf%25asd");
        t("http://host/%%%25%32%35asd%%", "http://host/%25%25%25asd%25%25");
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
        t("http://\1.com/", "http://%01.com/");
        t("http://notrailingslash.com", "http://notrailingslash.com/");
        t("http://www.gotaport.com:1234/", "http://gotaport.com:1234/");
        t("  http://www.google.com/  ", "http://google.com/");
        t("http:// leadingspace.com/", "http://%20leadingspace.com/");
        t("http://%20leadingspace.com/", "http://%20leadingspace.com/");
        t("%20leadingspace.com/", "http://%20leadingspace.com/");
        t("https://www.securesite.com/", "https://securesite.com/");
        t("http://host.com/ab%23cd", "http://host.com/ab%23cd");
        t("http://host.com//twoslashes?more//slashes", "http://host.com/twoslashes?more//slashes");
    }
}
