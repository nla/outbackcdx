<webapp>
    <sidebar active="{collection}"/>

    <div class="maincolumn">
        <tabs active="{tab}" />
        <main>
            <input name="seekBox" onchange="{loadData}">
            <table if="{tab == 'captures'}">
                <tr>
                    <th>Key</th>
                    <th>Date</th>
                    <th>URI</th>
                    <th>Type</th>
                    <th>Status</th>
                    <th>Length</th>
                    <th>File</th>
                    <th>Offset</th>
                    <th>Redirect</th>
                    <th>Digest</th>
                </tr>
                <tr each="{rows}">
                    <td>{urlkey}</td>
                    <td>{timestamp}</td>
                    <td>{original}</td>
                    <td>{mimetype}</td>
                    <td>{status}</td>
                    <td>{length}</td>
                    <td>{file}</td>
                    <td>{compressedoffset}</td>
                    <td>{redirecturl}</td>
                    <td>{digest}</td>
                </tr>
            </table>
            <table if="{tab == 'aliases'}">
                <tr>
                    <th>Alias</th>
                    <th>Target</th>
                </tr>
                <tr each="{rows}">
                    <td>{alias}</td>
                    <td>{target}</td>
                </tr>
            </table>
        </main>
    </div>

    <script>
        var self = this;
        self.collection = null;
        self.rows = [];
        self.start = "";
        self.tab = 'captures';


        self.loadData = function() {
            console.log("load data " + self.collection);
            var loader;
            if (self.tab == 'aliases') {
                loader = getAliases;
            } else {
                loader = getCaptures;
            }

            loader(self.collection, self.seekBox.value, 100, function(rows) {
                self.update({rows: rows});
            });
        }

        riot.route('/collections/*/captures', function (collection) {
            self.collection = collection;
            self.update({collection: collection, tab: 'captures'});
            self.loadData();
        });

        riot.route('/collections/*/aliases', function (collection) {
            self.collection = collection;
            self.update({collection: collection, tab: 'aliases'});
            self.loadData();
        });
    </script>

    <style scoped>
        :scope {
            display: flex;
        }

        a {
            text-decoration: none;
        }

        .maincolumn {
            display: flex;
            flex-direction: column;
        }

        main {
            border: 1px solid #ccc;
            flex-grow: 1;
        }

        input {
            width: 100%;
        }
    </style>
</webapp>

<sidebar>
    <ul>
        <li each="{ name, i in collections }" class="{active: (name == parent.opts.active) }">
            <a href="#collections/{name}/{parent.tab || 'captures'}">{ name }</a>
        </li>
    </ul>

    <style scoped>
        ul {
            list-style: none;
            margin: 0;
            padding: 0;
            margin-top: 40px;
        }


        li:first-child {
            border-top: 1px solid #ccc;
        }
        li {
            border-bottom: 1px solid #ccc;
            border-left: 1px solid #ccc;
        }
        li a {
            outline: 0;
            display: block;
            padding: 8px;
            color: #000;
        }
        li.active a {
            background: #ccc;
        }

    </style>

    <script>
        var self = this;
        self.collections = [];

        self.on('mount', function() {
            getJson("api/collections", function(collections) {
               self.update({collections: collections});
            });
        });
    </script>
</sidebar>

<tabs>
    <ul>
        <li class="{active: opts.active == 'captures'}"><a href="#collections/{collection}/captures">Captures</a></li>
        <li class="{active: opts.active == 'aliases'}"><a href="#collections/{collection}/aliases">Aliases</a></li>
    </ul>

    <style scoped>
        ul {
            display: flex;
            margin: 0;
            padding: 0;
        }

        li:first-child {
            border-left: 1px solid #ccc;
        }

        li a {
            padding: 8px;
            display: block;
            color: #000;
        }

        li {
            display: block;
            border-top: 1px solid #ccc;
            border-right: 1px solid #ccc;
        }

        li.active a {
            background: #ccc;
        }
    </style>
</tabs>