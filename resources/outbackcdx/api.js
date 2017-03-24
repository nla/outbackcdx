"use strict";

function CdxApi(baseUrl) {
    this.baseUrl = baseUrl;
}

(function() {

    function getJson(uri, success) {
        var request = new XMLHttpRequest();
        request.responseType = 'json';
        request.addEventListener('load', function () {
            if (request.status == 200) {
                success(request.response);
            }
        });
        request.open('GET', uri);
        request.send();
    }

    function encodeQueryString(data) {
        var qs = "";
        for (var key in data) {
            if (data.hasOwnProperty(key)) {
                if (qs !== "") {
                    qs += "&";
                }
                qs += encodeURIComponent(key) + "=" + encodeURIComponent(data[key]);
            }
        }
        return qs;
    }


    CdxApi.prototype.listCollections = function(success) {
        var baseUrl = this.baseUrl;
        getJson(baseUrl + "/api/collections", function (names) {
            success(names.map(function(name) {
                return new CdxCollection(baseUrl + "/" + name, name);
            }));
        });
    };

    CdxApi.prototype.collection = function(name) {
        return new CdxCollection(this.baseUrl + "/" + name, name);
    }

    function CdxCollection(baseUrl, name) {
        this.baseUrl = baseUrl;
        this.name = name;
    }

    CdxCollection.prototype.query = function(options, success) {
        options.output = "json";
        getJson(this.baseUrl + "?" + encodeQueryString(options), success);
    };


    CdxCollection.prototype.stats = function(success) {
        getJson(this.baseUrl + "/stats", success);
    };
    
    CdxCollection.prototype.accessRules = function(url, success) {
        getJson(this.baseUrl + "/access/rules?url=" + encodeURIComponent(url), success);
    };

    CdxCollection.prototype.saveAccessRule = function(rule, success) {
        var request = new XMLHttpRequest();
        request.open('POST', this.baseUrl + "/access/rules");
        request.setRequestHeader("Content-Type", "application/json");
        request.addEventListener('load', function() {
            if (request.status == 200 || request.status == 201) {
                success();
            }
        });
        request.send(rule);
    };


    function getCaptures(collection, key, limit, success) {
        var qs = encodeQueryString({key: key, limit: limit});
        getJson('api/collections/' + encodeURIComponent(collection) + '/captures?' + qs, success);
    }

    function getAliases(collection, key, limit, success) {
        var qs = encodeQueryString({key: key, limit: limit});
        getJson('api/collections/' + encodeURIComponent(collection) + '/aliases?' + qs, success);
    }

})();