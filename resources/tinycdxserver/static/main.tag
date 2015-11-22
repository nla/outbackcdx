<webapp>
    <sidebar active="{collection}"/>

    <div class="maincolumn">
        <tabs/>
        <main>
            <input name="seekBox">
            <table>
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
        </main>
    </div>

    <script>
        var self = this;
        self.collection = null;
        self.rows = [];
        self.start = "";

        riot.route('/collections/*', function (collection) {
            getCaptures(collection, self.seekBox.value, 100, function(rows) {
                self.update({rows: rows});
            });
            self.update({collection: collection});
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
            <a href="#collections/{name}">{ name }</a>
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
        }
        li.active a {
            color: #000;
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
        <li><a href="#collections/{collection}">Captures</a></li>
        <li><a href="#collections/{collection}/aliases">Aliases</a></li>
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
        }

        li {
            display: block;
            border-top: 1px solid #ccc;
            border-right: 1px solid #ccc;
        }
    </style>
</tabs>