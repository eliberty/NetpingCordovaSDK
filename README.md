# Plugin Cordova for Nepting Mpos #
==================================

Ludovic Menu @Eliberty Services SAS

INSTALL :
---------

cordova plugin add https://github.com/eliberty/PayzenCordovaPlugin


HOW TO USE IN EcmaScript 2015/ES6 :
-----------------------------------

```javascript
const params = {
  amount: 10000, // amount
  orderId: 1025123, // Id of Order
  nepwebUrl: '' // URL Nepting
  merchantId: 1265456464, // Merchant ID
  sentryDsn: 'https://XX:XX@sentry.io/XX', // URL sentry to log
  deviceId: qsdfqsd4f6q5s4df, // Android Id
};

const failedCallbackFunction = (data) => {
  console.info(`**** ELiberty **** Nepting failedCallbackFunction : ${JSON.stringify(data)}`);
  // ...
};

const successCallbackFunction = (data) => {
  console.info(`**** ELiberty **** Nepting successCallbackFunction : ${JSON.stringify(data)}`);
  switch (data.code) {
    case MposStatusCode.MESSAGE:
      // ...
      break;
    case MposStatusCode.REFUSED:
    case MposStatusCode.ERROR:
      // ...
      break;
    case MposStatusCode.SUCCESS:
      // ...
      break;
    case MposStatusCode.DECLINED:
      // ...
      break;
    case MposStatusCode.LOGIN_FAILED:
      // ...
      break;    
    default:
      // ...
      break;
  }
};

if (typeof window.plugins === 'undefined') return;

window.plugins.CordovaNepting.startActivity(
  params,
  successCallbackFunction,
  failedCallbackFunction,
);
```
