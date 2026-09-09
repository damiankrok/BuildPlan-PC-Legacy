package com.buildplan.app.analyzer

/**
 * A minimal, synthetic page in the *shape* of an ARCHON project page.
 *
 * Nothing here is copied from the live site: the project, its rooms and its
 * numbers are invented for the test, and only the markup skeleton — the class
 * names and attributes the adapter keys on — mirrors the real page. Keeping
 * it small and lawful is the point: adapter contracts are tested here, and
 * live behaviour is a separate opt-in smoke.
 */
object ArchonFixture {

    const val KEY = "mab12cd34ef56a"
    const val PAGE_URL = "https://www.archon.pl/projekty-domow/projekt-dom-testowy-x-$KEY"
    const val COST_URL = "https://www.archon.pl/projekty-domow/projekt-dom-testowy-x-$KEY-koszt-budowy-7"
    const val ASSETS = "https://assets.archon.pl/images/products/$KEY"

    val page: String = """
        <!doctype html><html><head>
        <title>Projekt domu Dom testowy (X) Dane projektu - ARCHON+</title>
        <link rel="canonical" href="$PAGE_URL" />
        <meta property="og:title" content="Dom testowy (X)" />
        <script>dataLayer.push({"pageType":"Produkt","projectName":"TEST_01_X","projectRoof":"dwuspadowy","projectGarage":"z-garazem-jednostanowiskowym"});</script>
        </head><body>
        <img alt="gotowy projekt Dom testowy (X) widok 1" src="$ASSETS/widok-1-projekt-dom-testowy-x-aaa__289.jpg" />
        <img alt="gotowy projekt Dom testowy (X) rzut parteru" data-floor-pom-img="$ASSETS/rzut-parteru-z-powierzchniami-projekt-dom-testowy-x-bbb__11915.gif" src="$ASSETS/projekt-dom-testowy-x-ccc__11815.gif" />
        <img alt="gotowy projekt Dom testowy (X) rzut poddasza" data-floor-pom-img="$ASSETS/rzut-poddasza-z-powierzchniami-projekt-dom-testowy-x-ddd__11917.gif" src="$ASSETS/projekt-dom-testowy-x-eee__11817.gif" />
        <img alt="gotowy projekt Dom testowy (X) przekroj budynku" src="$ASSETS/przekroj-budynku-projekt-dom-testowy-x-fff__256.jpg" />
        <img alt="gotowy projekt Dom testowy (X) sytuacja" src="$ASSETS/sytuacja-projekt-dom-testowy-x-ggg__255.jpg" />
        <img alt="Elewacja frontowa projekt dom testowy x hhh 264" data-images-modal-link="elevation1" src="$ASSETS/elewacja-frontowa-projekt-dom-testowy-x-hhh__264.jpg" />
        <img alt="Elewacja boczna projekt dom testowy x iii 265" data-images-modal-link="elevation2" src="$ASSETS/elewacja-boczna-projekt-dom-testowy-x-iii__265.jpg" />
        <img alt="Elewacja boczna projekt dom testowy x jjj 266" data-images-modal-link="elevation3" src="$ASSETS/elewacja-boczna-projekt-dom-testowy-x-jjj__266.jpg" />
        <img alt="Elewacja ogrodowa projekt dom testowy x kkk 267" data-images-modal-link="elevation4" src="$ASSETS/elewacja-ogrodowa-projekt-dom-testowy-x-kkk__267.jpg" />
        <img alt="other project" src="https://assets.archon.pl/images/products/ffffffffffffff/widok-1-x__289.jpg" />
        <a href="/projekty-domow/projekt-dom-testowy-x-$KEY-koszt-budowy-7#product-heading">Koszt budowy</a>

        <table class="table table-striped table-hover">
          <thead><tr><th>PARTER<br></th><th class="w-25" title="powierzchnia użytkowa">60,00</th><th class="w-25" title="powierzchnia podłogi"> (61,20) </th></tr></thead>
          <tbody>
            <tr><td >1. Wiatrołap</td><td title="powierzchnia użytkowa">4,00</td><td title="powierzchnia podłogi"></td></tr>
            <tr><td >2. Salon + Jadalnia</td><td title="powierzchnia użytkowa">30,00</td><td title="powierzchnia podłogi"></td></tr>
            <tr><td >3. Kuchnia</td><td title="powierzchnia użytkowa">10,00</td><td title="powierzchnia podłogi"> (10,40)</td></tr>
            <tr><td >4. Garaż</td><td title="powierzchnia użytkowa">16,00</td><td title="powierzchnia podłogi"></td></tr>
          </tbody>
        </table>
        <table class="table table-striped table-hover">
          <thead><tr><th>PODDASZE<br></th><th class="w-25" title="powierzchnia użytkowa">40,00</th><th class="w-25" title="powierzchnia podłogi"> (52,00) </th></tr></thead>
          <tbody>
            <tr><td >1. Pokój</td><td title="powierzchnia użytkowa">20,00</td><td title="powierzchnia podłogi"> (26,00)</td></tr>
            <tr><td >2. Pokój</td><td title="powierzchnia użytkowa">20,00</td><td title="powierzchnia podłogi"> (26,00)</td></tr>
          </tbody>
        </table>

        <div class="product-data__item embedded-json__item" data-resource="powierzchnia-netto-domu-br-bez-kotlowni-garazu">
          <div class="product-data__header"><div class="product-data__title">Powierzchnia netto domu<br/>bez kotłowni, garażu</div>
          <div class="product-data__value">84 <span>m&sup2;</span></div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="powierzchnia-garazu">
          <div class="product-data__header"><div class="product-data__title">Powierzchnia garażu</div>
          <div class="product-data__value">16 <span>m&sup2;</span></div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="powierzchnia-zabudowy">
          <div class="product-data__header"><div class="product-data__title">Powierzchnia zabudowy</div>
          <div class="product-data__value">80,50 <span>m&sup2;</span></div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="kubatura">
          <div class="product-data__header"><div class="product-data__title">Kubatura</div>
          <div class="product-data__value">500,5 <span>m&sup3;</span></div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="powierzchnia-dachu">
          <div class="product-data__header"><div class="product-data__title">Powierzchnia dachu</div>
          <div class="product-data__value">120,25 <span>m&sup2;</span></div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="wysokosc-budynku">
          <div class="product-data__header"><div class="product-data__title">Wysokość budynku</div>
          <div class="product-data__value">8,1 <span>m</span></div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="minimalne-wymiary-dzialki">
          <div class="product-data__header"><div class="product-data__title">Minimalne wymiary działki</div>
          <div class="product-data__value">17,5 x 20,05 m</div></div></div>
        <div class="product-data__item embedded-json__item" data-resource="construction">
          <div class="product-data__header"><div class="product-data__title">Konstrukcja</div><div class="product-data__value"></div></div>
        </div>
        <div class="product-data technical-data-item">
          <div class="product-data__item"><div class="product-data__header"><div class="product-data__title"><strong>ściany:</strong> pustak ceramiczny 25 cm, styropian 20 cm, tynk </div></div></div>
          <div class="product-data__item"><div class="product-data__header"><div class="product-data__title"><strong>strop:</strong> płyta żelbetowa </div></div></div>
          <div class="product-data__item"><div class="product-data__header"><div class="product-data__title"><strong>ścianka kolankowa:</strong> 120 cm  </div></div></div>
          <div class="product-data__item"><div class="product-data__header"><div class="product-data__title"><strong>dach:</strong> dwuspadowy, nachylenie 42 st. , więźba drewniana, dachówka ceramiczna </div></div></div>
        </div>
        </body></html>
    """.trimIndent()

    val costPage: String = """
        <!doctype html><html><head><link rel="canonical" href="$COST_URL" /></head><body>
        <div id="dane-do-kalkulacji">
          <div class="headline"><h3>Dane do kalkulacji</h3></div>
          <div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia ścian fundamentowych</p><p class="col-xs-3"><b>30,00 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia ścian zewnętrznych</p><p class="col-xs-3"><b>100,50 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia ścian wewnętrznych nośnych</p><p class="col-xs-3"><b>20,00 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia ścian działowych parter</p><p class="col-xs-3"><b>40,00 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia ścian działowych poddasze</p><p class="col-xs-3"><b>50,00 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia podłóg i schodów</p><p class="col-xs-3"><b>113,20 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia stolarki zewnętrznej</p><p class="col-xs-3"><b>25,00 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia elewacji do ocieplenia </p><p class="col-xs-3"><b>180,00 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Powierzchnia dachu</p><p class="col-xs-3"><b>120,25 m<sup>2</sup></b></p></div>
            <div class="row cost-table"><p class="col-xs-9">Kubatura drewna na więźbę</p><p class="col-xs-3"><b>5,00 m<sup>3</sup></b></p></div>
          </div>
        </div></body></html>
    """.trimIndent()

    /** A catalogue page: what a stale project URL redirects to. Not a project page. */
    val catalogue: String = """
        <!doctype html><html><head><title>Projekty domów - ARCHON+</title>
        <link rel="canonical" href="https://www.archon.pl/projekty-domow" /></head><body>catalogue</body></html>
    """.trimIndent()
}
