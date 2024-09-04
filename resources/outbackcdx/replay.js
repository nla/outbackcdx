async function init() {
    const path = window.location.pathname;
    const segments = path.split("/");
    const collection = segments[1];
    const url = path.substring(segments[1].length + segments[2].length + 3);

    const collAdded = new Promise((resolve) => {
        navigator.serviceWorker.addEventListener('message', (event) => {
            if (event.data.msg_type === 'collAdded') {
                resolve();
            }
        });
    });

    await navigator.serviceWorker.register("/sw.js");

    if (navigator.serviceWorker.controller || (await navigator.serviceWorker.ready).active) {
        navigator.serviceWorker.controller.postMessage({
            msg_type: 'addColl',
            name: collection,
            type: 'live',
            file: {'sourceUrl': 'proxy:'},
            extraConfig: {
                baseUrl: window.location.href,
                isLive: false,
                archivePrefix: '/' + segments[1] + '/',
            }
        });
    }

    window.addEventListener('message', event => {
        let data = event.data;
        if (data.wb_type === 'title') {
            document.title = data.title;
        } else if (data.wb_type === 'replace-url') {
            history.replaceState({}, data.title, '/' + collection + '/' + data.ts + '/' + data.url)
        }
    });

    await collAdded;

    const style = document.createElement("style");
    style.innerHTML = 'html, body, iframe { margin:0; padding:0; width: 100%; height: 100% }';
    const iframe = document.createElement('iframe');
    iframe.src = '/w/' + segments[1] + '/' + segments[2] + 'mp_/' + url;
    document.body.append(style, iframe);
}

init();