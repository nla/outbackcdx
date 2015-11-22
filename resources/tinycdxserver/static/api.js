function getJson(uri, success) {
    var request = new XMLHttpRequest();
    request.addEventListener('load', function() {
        if (request.status == 200) {
            success(JSON.parse(request.responseText));
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

function getCaptures(collection, key, limit, success) {
    var qs = encodeQueryString({key: key, limit: limit});
    getJson('api/collections/' +  encodeURIComponent(collection) + '/captures?' + qs, success);
}