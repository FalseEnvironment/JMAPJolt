package com.falseenvironment.jmapjolt

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

object FaviconRepository {

    private const val CACHE_MAX_SIZE = 1000
    private const val CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000
    private const val NEGATIVE_CACHE_TTL_MS = 24L * 60 * 60 * 1000
    private const val NEGATIVE_CACHE_MAX_SIZE = 2000

    private data class CacheEntry(val bitmap: Bitmap, val fetchedAt: Long)

    private val cache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>) = size > CACHE_MAX_SIZE
    }

    private val negativeCache = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, Long>) = size > NEGATIVE_CACHE_MAX_SIZE
    }

    @Volatile private var diskCacheDir: java.io.File? = null

    fun init(cacheDir: java.io.File) {
        diskCacheDir = java.io.File(cacheDir, "favicons").also { it.mkdirs() }
    }

    private fun domainToFilename(domain: String) =
        domain.replace(".", "_").replace("/", "_") + ".png"

    private fun readFromDisk(domain: String): CacheEntry? {
        val dir = diskCacheDir ?: return null
        val file = java.io.File(dir, domainToFilename(domain))
        if (!file.exists()) return null
        val age = System.currentTimeMillis() - file.lastModified()
        if (age > CACHE_TTL_MS) { file.delete(); return null }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        return CacheEntry(bitmap, file.lastModified())
    }

    private fun writeToDisk(domain: String, bitmap: Bitmap) {
        val dir = diskCacheDir ?: return
        val file = java.io.File(dir, domainToFilename(domain))
        try {
            java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        } catch (_: Exception) { file.delete() }
    }

    private fun writeNegativeToDisk(domain: String) {
        val dir = diskCacheDir ?: return
        val marker = java.io.File(dir, domainToFilename(domain) + ".neg")
        try { marker.createNewFile() } catch (_: Exception) {}
    }

    private fun isNegativeOnDisk(domain: String): Boolean {
        val dir = diskCacheDir ?: return false
        val marker = java.io.File(dir, domainToFilename(domain) + ".neg")
        if (!marker.exists()) return false
        val age = System.currentTimeMillis() - marker.lastModified()
        if (age > NEGATIVE_CACHE_TTL_MS) { marker.delete(); return false }
        return true
    }

    // Known multi-part TLDs where the registrable domain includes one extra label
    private val MULTI_PART_TLDS = setOf(
        // .ac
        "com.ac", "gov.ac", "mil.ac", "net.ac", "org.ac",
        // .ae
        "ac.ae", "co.ae", "gov.ae", "mil.ae", "name.ae", "net.ae", "org.ae", "pro.ae", "sch.ae",
        // .af
        "com.af", "edu.af", "gov.af", "net.af", "org.af",
        // .al
        "com.al", "edu.al", "gov.al", "mil.al", "net.al", "org.al",
        // .ao
        "co.ao", "ed.ao", "gv.ao", "it.ao", "og.ao", "pb.ao",
        // .ar
        "com.ar", "edu.ar", "gob.ar", "gov.ar", "int.ar", "mil.ar", "net.ar", "org.ar", "tur.ar",
        // .at
        "ac.at", "co.at", "gv.at", "or.at",
        // .au
        "asn.au", "com.au", "csiro.au", "edu.au", "gov.au", "id.au", "net.au", "org.au",
        // .ba
        "co.ba", "com.ba", "edu.ba", "gov.ba", "mil.ba", "net.ba", "org.ba", "rs.ba",
        // .bb
        "biz.bb", "co.bb", "com.bb", "edu.bb", "gov.bb", "info.bb", "net.bb", "org.bb",
        // .bh
        "biz.bh", "cc.bh", "com.bh", "edu.bh", "gov.bh", "info.bh", "net.bh", "org.bh",
        // .bn
        "com.bn", "edu.bn", "gov.bn", "net.bn", "org.bn",
        // .bo
        "com.bo", "edu.bo", "gob.bo", "gov.bo", "int.bo", "mil.bo", "net.bo", "org.bo", "tv.bo",
        // .br
        "adm.br", "adv.br", "agr.br", "am.br", "arq.br", "art.br", "ato.br", "b.br",
        "bio.br", "blog.br", "bmd.br", "cim.br", "cng.br", "cnt.br", "com.br", "coop.br",
        "ecn.br", "edu.br", "eng.br", "esp.br", "etc.br", "eti.br", "far.br", "flog.br",
        "fm.br", "fnd.br", "fot.br", "fst.br", "g12.br", "ggf.br", "gov.br", "imb.br",
        "ind.br", "inf.br", "jor.br", "jus.br", "lel.br", "mat.br", "med.br", "mil.br",
        "mus.br", "net.br", "nom.br", "not.br", "ntr.br", "odo.br", "org.br", "ppg.br",
        "pro.br", "psc.br", "psi.br", "qsl.br", "rec.br", "slg.br", "srv.br", "tmp.br",
        "trd.br", "tur.br", "tv.br", "vet.br", "vlog.br", "wiki.br", "zlg.br",
        // .bs
        "com.bs", "edu.bs", "gov.bs", "net.bs", "org.bs",
        // .bz
        "com.bz", "edu.bz", "gov.bz", "net.bz", "org.bz",
        // .ca
        "ab.ca", "bc.ca", "mb.ca", "nb.ca", "nf.ca", "nl.ca", "ns.ca", "nt.ca",
        "nu.ca", "on.ca", "pe.ca", "qc.ca", "sk.ca", "yk.ca",
        // .ck
        "biz.ck", "co.ck", "edu.ck", "gen.ck", "gov.ck", "info.ck", "net.ck", "org.ck",
        // .cn
        "ac.cn", "ah.cn", "bj.cn", "com.cn", "cq.cn", "edu.cn", "fj.cn", "gd.cn",
        "gov.cn", "gs.cn", "gx.cn", "gz.cn", "ha.cn", "hb.cn", "he.cn", "hi.cn",
        "hl.cn", "hn.cn", "jl.cn", "js.cn", "jx.cn", "ln.cn", "mil.cn", "net.cn",
        "nm.cn", "nx.cn", "org.cn", "qh.cn", "sc.cn", "sd.cn", "sh.cn", "sn.cn",
        "sx.cn", "tj.cn", "xj.cn", "xz.cn", "yn.cn", "zj.cn",
        // .co
        "arts.co", "com.co", "edu.co", "firm.co", "gov.co", "info.co", "int.co",
        "mil.co", "net.co", "nom.co", "org.co", "rec.co", "web.co",
        // .cr
        "ac.cr", "co.cr", "com.cr", "ed.cr", "fi.cr", "go.cr", "or.cr", "sa.cr",
        // .cy
        "ac.cy", "biz.cy", "com.cy", "ekloges.cy", "gov.cy", "ltd.cy", "mil.cy",
        "net.cy", "org.cy", "press.cy", "pro.cy", "tm.cy",
        // .do
        "art.do", "com.do", "edu.do", "gob.do", "gov.do", "mil.do", "net.do",
        "org.do", "sld.do", "web.do",
        // .dz
        "art.dz", "asso.dz", "com.dz", "edu.dz", "gov.dz", "net.dz", "org.dz", "pol.dz",
        // .ec
        "com.ec", "edu.ec", "fin.ec", "gov.ec", "info.ec", "med.ec", "mil.ec",
        "net.ec", "org.ec", "pro.ec",
        // .eg
        "com.eg", "edu.eg", "eun.eg", "gov.eg", "mil.eg", "name.eg", "net.eg",
        "org.eg", "sci.eg",
        // .er
        "com.er", "edu.er", "gov.er", "mil.er", "net.er", "org.er",
        // .es
        "com.es", "edu.es", "gob.es", "nom.es", "org.es",
        // .et
        "biz.et", "com.et", "edu.et", "gov.et", "info.et", "name.et", "net.et", "org.et",
        // .fj
        "ac.fj", "biz.fj", "com.fj", "gov.fj", "info.fj", "mil.fj", "name.fj",
        "net.fj", "org.fj", "pro.fj",
        // .fk
        "ac.fk", "co.fk", "gov.fk", "net.fk", "nom.fk", "org.fk",
        // .fr
        "asso.fr", "com.fr", "gouv.fr", "nom.fr", "prd.fr", "tm.fr",
        // .ge
        "com.ge", "edu.ge", "gov.ge", "mil.ge", "net.ge", "org.ge", "pvt.ge",
        // .gh
        "com.gh", "edu.gh", "gov.gh", "mil.gh", "org.gh",
        // .gn
        "ac.gn", "com.gn", "gov.gn", "net.gn", "org.gn",
        // .gr
        "com.gr", "edu.gr", "gov.gr", "mil.gr", "net.gr", "org.gr",
        // .gt
        "com.gt", "edu.gt", "gob.gt", "ind.gt", "mil.gt", "net.gt", "org.gt",
        // .gu
        "com.gu", "edu.gu", "gov.gu", "net.gu", "org.gu",
        // .hk
        "com.hk", "edu.hk", "gov.hk", "idv.hk", "net.hk", "org.hk",
        // .id
        "ac.id", "co.id", "go.id", "mil.id", "net.id", "or.id", "sch.id", "web.id",
        // .il
        "ac.il", "co.il", "gov.il", "idf.il", "k12.il", "muni.il", "net.il", "org.il",
        // .in
        "4fd.in", "ac.in", "co.in", "edu.in", "ernet.in", "firm.in", "gen.in", "gov.in",
        "ind.in", "mil.in", "net.in", "nic.in", "org.in", "res.in",
        // .iq
        "com.iq", "edu.iq", "gov.iq", "mil.iq", "net.iq", "org.iq",
        // .ir
        "ac.ir", "co.ir", "dnssec.ir", "gov.ir", "id.ir", "net.ir", "org.ir", "sch.ir",
        // .it
        "edu.it", "gov.it",
        // .je
        "co.je", "net.je", "org.je",
        // .jo
        "com.jo", "edu.jo", "gov.jo", "mil.jo", "name.jo", "net.jo", "org.jo", "sch.jo",
        // .jp
        "ac.jp", "ad.jp", "co.jp", "ed.jp", "go.jp", "gr.jp", "lg.jp", "ne.jp", "or.jp",
        // .ke
        "ac.ke", "co.ke", "go.ke", "info.ke", "me.ke", "mobi.ke", "ne.ke", "or.ke", "sc.ke",
        // .kh
        "com.kh", "edu.kh", "gov.kh", "mil.kh", "net.kh", "org.kh", "per.kh",
        // .ki
        "biz.ki", "com.ki", "de.ki", "edu.ki", "gov.ki", "info.ki", "mob.ki",
        "net.ki", "org.ki", "tel.ki",
        // .km
        "asso.km", "com.km", "coop.km", "edu.km", "gouv.km", "k12.km",
        "medecin.km", "mil.km", "nom.km", "notaires.km", "org.km", "pharmaciens.km",
        "presse.km", "tm.km", "veterinaire.km",
        // .kn
        "edu.kn", "gov.kn", "net.kn", "org.kn",
        // .kr
        "ac.kr", "busan.kr", "chungbuk.kr", "chungnam.kr", "co.kr", "daegu.kr",
        "daejeon.kr", "es.kr", "gangwon.kr", "go.kr", "gwangju.kr", "gyeongbuk.kr",
        "gyeonggi.kr", "gyeongnam.kr", "hs.kr", "incheon.kr", "jeju.kr",
        "jeonbuk.kr", "jeonnam.kr", "k12.kr", "kg.kr", "mil.kr", "ms.kr",
        "ne.kr", "or.kr", "pe.kr", "re.kr", "sc.kr", "seoul.kr", "ulsan.kr",
        // .kw
        "com.kw", "edu.kw", "emb.kw", "gov.kw", "ind.kw", "net.kw", "org.kw",
        // .ky
        "com.ky", "edu.ky", "gov.ky", "net.ky", "org.ky",
        // .kz
        "com.kz", "edu.kz", "gov.kz", "mil.kz", "net.kz", "org.kz",
        // .la
        "com.la", "net.la", "org.la",
        // .lb
        "com.lb", "edu.lb", "gov.lb", "net.lb", "org.lb",
        // .lk
        "assn.lk", "com.lk", "edu.lk", "gov.lk", "grp.lk", "hotel.lk", "int.lk",
        "ltd.lk", "net.lk", "ngo.lk", "org.lk", "sch.lk", "soc.lk", "web.lk",
        // .lr
        "com.lr", "edu.lr", "gov.lr", "net.lr", "org.lr",
        // .ls
        "ac.ls", "biz.ls", "co.ls", "edu.ls", "gov.ls", "info.ls", "net.ls",
        "org.ls", "sc.ls",
        // .ly
        "com.ly", "edu.ly", "gov.ly", "id.ly", "med.ly", "net.ly", "org.ly", "plc.ly",
        // .ma
        "ac.ma", "co.ma", "com.ma", "gov.ma", "net.ma", "org.ma", "press.ma",
        // .mc
        "asso.mc", "tm.mc",
        // .me
        "ac.me", "co.me", "edu.me", "gov.me", "its.me", "net.me", "org.me",
        // .mg
        "com.mg", "edu.mg", "gov.mg", "mil.mg", "nom.mg", "org.mg", "prd.mg", "tm.mg",
        // .mk
        "com.mk", "edu.mk", "gov.mk", "inf.mk", "name.mk", "net.mk", "org.mk",
        // .ml
        "com.ml", "edu.ml", "gov.ml", "net.ml", "org.ml",
        // .mn
        "edu.mn", "gov.mn", "org.mn",
        // .mo
        "com.mo", "edu.mo", "gov.mo", "net.mo", "org.mo",
        // .mt
        "com.mt", "edu.mt", "gov.mt", "net.mt", "org.mt",
        // .mu
        "ac.mu", "co.mu", "com.mu", "gov.mu", "net.mu", "org.mu",
        // .mv
        "aero.mv", "biz.mv", "com.mv", "coop.mv", "edu.mv", "gov.mv", "info.mv",
        "int.mv", "mil.mv", "museum.mv", "name.mv", "net.mv", "org.mv", "pro.mv",
        // .mw
        "ac.mw", "biz.mw", "co.mw", "com.mw", "coop.mw", "edu.mw", "gov.mw",
        "int.mw", "museum.mw", "net.mw", "org.mw",
        // .mx
        "com.mx", "edu.mx", "gob.mx", "net.mx", "org.mx",
        // .my
        "com.my", "edu.my", "gov.my", "mil.my", "name.my", "net.my", "org.my", "sch.my",
        // .mz
        "ac.mz", "co.mz", "edu.mz", "gov.mz", "org.mz",
        // .na
        "co.na", "com.na",
        // .nf
        "arts.nf", "com.nf", "firm.nf", "info.nf", "net.nf", "other.nf", "per.nf",
        "rec.nf", "store.nf", "web.nf",
        // .ng
        "biz.ng", "com.ng", "edu.ng", "gov.ng", "mil.ng", "mobi.ng", "name.ng",
        "net.ng", "org.ng", "sch.ng",
        // .ni
        "ac.ni", "co.ni", "com.ni", "edu.ni", "gob.ni", "mil.ni", "net.ni", "nom.ni", "org.ni",
        // .np
        "com.np", "edu.np", "gov.np", "mil.np", "net.np", "org.np",
        // .nr
        "biz.nr", "com.nr", "edu.nr", "gov.nr", "info.nr", "net.nr", "org.nr",
        // .nz
        "ac.nz", "co.nz", "cri.nz", "geek.nz", "gen.nz", "govt.nz", "health.nz",
        "iwi.nz", "kiwi.nz", "maori.nz", "mil.nz", "net.nz", "org.nz", "parliament.nz",
        "school.nz",
        // .om
        "ac.om", "biz.om", "co.om", "com.om", "edu.om", "gov.om", "med.om",
        "mil.om", "museum.om", "net.om", "org.om", "pro.om",
        // .pa
        "abo.pa", "ac.pa", "com.pa", "edu.pa", "gob.pa", "ing.pa", "med.pa",
        "net.pa", "nom.pa", "org.pa", "sld.pa",
        // .pe
        "com.pe", "edu.pe", "gob.pe", "mil.pe", "net.pe", "nom.pe", "org.pe",
        // .pf
        "com.pf", "edu.pf", "org.pf",
        // .pg
        "com.pg", "net.pg", "org.pg",
        // .ph
        "com.ph", "edu.ph", "gov.ph", "i.ph", "mil.ph", "net.ph", "ngo.ph", "org.ph",
        // .pk
        "biz.pk", "com.pk", "edu.pk", "fam.pk", "gob.pk", "gok.pk", "gon.pk",
        "gop.pk", "gos.pk", "gov.pk", "net.pk", "org.pk", "web.pk",
        // .pl
        "agro.pl", "atm.pl", "auto.pl", "biz.pl", "com.pl", "edu.pl", "gmina.pl",
        "gsm.pl", "info.pl", "mail.pl", "media.pl", "mil.pl", "net.pl", "nieruchomosci.pl",
        "nom.pl", "org.pl", "pc.pl", "powiat.pl", "priv.pl", "realestate.pl",
        "rel.pl", "sex.pl", "shop.pl", "sklep.pl", "sos.pl", "szkola.pl",
        "targi.pl", "tm.pl", "tourism.pl", "travel.pl", "turystyka.pl",
        // .pr
        "ac.pr", "biz.pr", "com.pr", "edu.pr", "est.pr", "gov.pr", "info.pr",
        "isla.pr", "name.pr", "net.pr", "org.pr", "pro.pr",
        // .ps
        "com.ps", "edu.ps", "gov.ps", "net.ps", "org.ps", "plo.ps", "sec.ps",
        // .pt
        "com.pt", "edu.pt", "gov.pt", "int.pt", "net.pt", "nome.pt", "org.pt", "publ.pt",
        // .pw
        "belau.pw", "co.pw", "ed.pw", "go.pw", "ne.pw", "or.pw",
        // .py
        "com.py", "edu.py", "gov.py", "mil.py", "net.py", "org.py",
        // .qa
        "com.qa", "edu.qa", "gov.qa", "mil.qa", "name.qa", "net.qa", "org.qa", "sch.qa",
        // .re
        "asso.re", "com.re", "nom.re",
        // .ro
        "arts.ro", "com.ro", "firm.ro", "info.ro", "nom.ro", "nt.ro", "org.ro",
        "rec.ro", "store.ro", "tm.ro", "www.ro",
        // .rs
        "ac.rs", "co.rs", "edu.rs", "gov.rs", "in.rs", "org.rs",
        // .rw
        "ac.rw", "co.rw", "com.rw", "edu.rw", "gouv.rw", "gov.rw", "int.rw",
        "mil.rw", "net.rw",
        // .sa
        "com.sa", "edu.sa", "gov.sa", "med.sa", "net.sa", "org.sa", "pub.sa", "sch.sa",
        // .sb
        "com.sb", "edu.sb", "gov.sb", "net.sb", "org.sb",
        // .sc
        "com.sc", "edu.sc", "gov.sc", "net.sc", "org.sc",
        // .sd
        "com.sd", "edu.sd", "gov.sd", "info.sd", "med.sd", "net.sd", "org.sd", "tv.sd",
        // .se
        "a.se", "ac.se", "b.se", "bd.se", "brand.se", "c.se", "d.se", "e.se",
        "f.se", "fh.se", "fhsk.se", "fhv.se", "g.se", "h.se", "i.se", "k.se",
        "komforb.se", "kommunalforbund.se", "komvux.se", "l.se", "lanbib.se",
        "m.se", "n.se", "naturbruksgymn.se", "o.se", "org.se", "p.se", "parti.se",
        "pp.se", "press.se", "r.se", "s.se", "t.se", "tm.se", "u.se", "w.se",
        "x.se", "y.se", "z.se",
        // .sg
        "com.sg", "edu.sg", "gov.sg", "idn.sg", "net.sg", "org.sg", "per.sg",
        // .sl
        "com.sl", "edu.sl", "gov.sl", "net.sl", "org.sl",
        // .sn
        "art.sn", "com.sn", "edu.sn", "gouv.sn", "org.sn", "perso.sn", "univ.sn",
        // .so
        "com.so", "edu.so", "gov.so", "net.so", "org.so",
        // .st
        "co.st", "com.st", "consulado.st", "edu.st", "embaixada.st", "gov.st",
        "mil.st", "net.st", "org.st", "principe.st", "saotome.st", "store.st",
        // .sv
        "com.sv", "edu.sv", "gob.sv", "org.sv", "red.sv",
        // .sy
        "com.sy", "edu.sy", "gov.sy", "mil.sy", "net.sy", "news.sy", "org.sy",
        // .sz
        "ac.sz", "co.sz", "org.sz",
        // .tc
        "com.tc", "edu.tc", "gov.tc", "net.tc", "org.tc",
        // .tj
        "ac.tj", "biz.tj", "co.tj", "com.tj", "edu.tj", "go.tj", "gov.tj",
        "int.tj", "mil.tj", "name.tj", "net.tj", "nic.tj", "org.tj", "web.tj",
        // .tn
        "agrinet.tn", "com.tn", "defense.tn", "edunet.tn", "ens.tn", "fin.tn",
        "gov.tn", "ind.tn", "info.tn", "intl.tn", "mincom.tn", "nat.tn", "net.tn",
        "org.tn", "perso.tn", "rnrt.tn", "rns.tn", "rnu.tn", "tourism.tn",
        // .to
        "com.to", "edu.to", "gov.to", "mil.to", "net.to", "org.to",
        // .tr
        "av.tr", "bbs.tr", "bel.tr", "biz.tr", "com.tr", "dr.tr", "edu.tr",
        "gen.tr", "gov.tr", "info.tr", "k12.tr", "mil.tr", "name.tr", "net.tr",
        "org.tr", "pol.tr", "tel.tr", "tv.tr", "web.tr",
        // .tt
        "biz.tt", "co.tt", "com.tt", "edu.tt", "gov.tt", "info.tt", "int.tt",
        "net.tt", "org.tt", "pro.tt",
        // .tw
        "club.tw", "com.tw", "ebiz.tw", "edu.tw", "game.tw", "gov.tw", "idv.tw",
        "mil.tw", "net.tw", "org.tw",
        // .tz
        "ac.tz", "co.tz", "go.tz", "hotel.tz", "info.tz", "me.tz", "mil.tz",
        "mobi.tz", "ne.tz", "or.tz", "sc.tz", "tv.tz",
        // .ua
        "ac.ua", "cherkassy.ua", "chernigov.ua", "chernovtsy.ua", "ck.ua",
        "cn.ua", "co.ua", "com.ua", "crimea.ua", "cv.ua", "dn.ua",
        "dnepropetrovsk.ua", "donetsk.ua", "dp.ua", "edu.ua", "gov.ua",
        "if.ua", "in.ua", "ivano-frankivsk.ua", "kh.ua", "kharkov.ua",
        "kherson.ua", "khmelnitskiy.ua", "kiev.ua", "kirovograd.ua", "km.ua",
        "kr.ua", "ks.ua", "kv.ua", "kyiv.ua", "lg.ua", "lt.ua", "lugansk.ua",
        "lutsk.ua", "lv.ua", "lviv.ua", "mk.ua", "mykolaiv.ua", "net.ua",
        "nikolaev.ua", "od.ua", "odessa.ua", "org.ua", "pl.ua", "poltava.ua", "pp.ua",
        "rovno.ua", "rv.ua", "sebastopol.ua", "sumy.ua", "te.ua", "ternopil.ua",
        "uzhgorod.ua", "vinnica.ua", "vn.ua", "zaporizhzhe.ua", "zhitomir.ua",
        "zp.ua", "zt.ua",
        // .ug
        "ac.ug", "co.ug", "go.ug", "ne.ug", "or.ug", "org.ug", "sc.ug",
        // .uk
        "ac.uk", "bl.uk", "british-library.uk", "co.uk", "cym.uk", "gov.uk", "govt.uk",
        "icnet.uk", "jet.uk", "lea.uk", "ltd.uk", "me.uk", "mil.uk", "mod.uk",
        "national-library-scotland.uk", "nel.uk", "net.uk", "nhs.uk", "nic.uk",
        "nls.uk", "org.uk", "orgn.uk", "parliament.uk", "plc.uk", "police.uk",
        "sch.uk", "scot.uk", "soc.uk",
        // .us
        "4fd.us", "dni.us", "fed.us", "isa.us", "kids.us", "nsn.us",
        // .uy
        "com.uy", "edu.uy", "gub.uy", "mil.uy", "net.uy", "org.uy",
        // .ve
        "co.ve", "com.ve", "edu.ve", "gob.ve", "info.ve", "mil.ve", "net.ve", "org.ve", "web.ve",
        // .vi
        "co.vi", "com.vi", "k12.vi", "net.vi", "org.vi",
        // .vn
        "ac.vn", "biz.vn", "com.vn", "edu.vn", "gov.vn", "health.vn", "info.vn",
        "int.vn", "name.vn", "net.vn", "org.vn", "pro.vn",
        // .ye
        "co.ye", "com.ye", "edu.ye", "gov.ye", "ltd.ye", "me.ye", "net.ye", "org.ye",
        // .yu
        "ac.yu", "co.yu", "edu.yu", "gov.yu", "org.yu",
        // .za
        "ac.za", "agric.za", "alt.za", "bourse.za", "city.za", "co.za", "cybernet.za",
        "db.za", "edu.za", "gov.za", "grondar.za", "iaccess.za", "imt.za",
        "inca.za", "inga.za", "internet.za", "landesign.za", "law.za", "mil.za",
        "net.za", "ngo.za", "nis.za", "nom.za", "olivetti.za", "org.za",
        "pix.za", "school.za", "tm.za", "web.za",
        // .zm
        "ac.zm", "co.zm", "com.zm", "edu.zm", "gov.zm", "net.zm", "org.zm", "sch.zm",
        // .zw
        "ac.zw", "co.zw", "gov.zw", "mil.zw", "org.zw"
    )

    fun getRootDomain(domain: String): String {
        val parts = domain.split(".")
        if (parts.size <= 2) return domain
        val lastTwo = parts.takeLast(2).joinToString(".")
        return if (MULTI_PART_TLDS.contains(lastTwo) && parts.size >= 3) {
            parts.takeLast(3).joinToString(".")
        } else {
            lastTwo
        }
    }

    suspend fun fetchFavicon(rawDomain: String): Bitmap? = withContext(Dispatchers.IO) {
        val domain = getRootDomain(rawDomain.lowercase())

        synchronized(negativeCache) {
            val negTime = negativeCache[domain]
            if (negTime != null && System.currentTimeMillis() - negTime < NEGATIVE_CACHE_TTL_MS) {
                return@withContext null
            }
        }

        synchronized(cache) {
            val entry = cache[domain]
            if (entry != null && System.currentTimeMillis() - entry.fetchedAt < CACHE_TTL_MS) {
                return@withContext entry.bitmap
            }
        }

        // Disk cache — survives app restarts
        if (isNegativeOnDisk(domain)) return@withContext null
        readFromDisk(domain)?.let { entry ->
            synchronized(cache) { cache[domain] = entry }
            return@withContext entry.bitmap
        }

        val bytes = fetchBytes("https://icons.duckduckgo.com/ip3/$domain.ico")
            ?: fetchBytes("https://$domain/favicon.ico")

        if (bytes == null || bytes.size < 10) {
            synchronized(negativeCache) { negativeCache[domain] = System.currentTimeMillis() }
            writeNegativeToDisk(domain)
            return@withContext null
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            synchronized(negativeCache) { negativeCache[domain] = System.currentTimeMillis() }
            writeNegativeToDisk(domain)
            return@withContext null
        }

        synchronized(cache) { cache[domain] = CacheEntry(bitmap, System.currentTimeMillis()) }
        writeToDisk(domain, bitmap)
        bitmap
    }

    private fun fetchBytes(urlString: String): ByteArray? {
        val conn = URL(urlString).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (conn.responseCode != 200) return null
            conn.inputStream.use { it.readBytes() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
