/**
 * cordova CordovaNepting plugin
 * Copyright (c) Eliberty Services SAS by Ludovic Menu
 *
 */
 (function(cordova){
    var CordovaNepting = function() {};


    CordovaNepting.prototype.startActivity = function (params, success, fail) {
        return cordova.exec(function (args) {
            success(args);
        }, function (args) {
            fail(args);
        }, 'CordovaNepting', 'startActivity', [params]);
    };

    window.CordovaNepting = new CordovaNepting();

    // backwards compatibility
    window.plugins = window.plugins || {};
    window.plugins.CordovaNepting = window.CordovaNepting;

})(window.PhoneGap || window.Cordova || window.cordova);
