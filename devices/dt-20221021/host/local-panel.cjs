'use strict';
const net=require('node:net');
const server=net.createServer(client=>{
 const modem=net.createConnection({host:'192.168.101.1',port:8000});
 client.setTimeout(180000,()=>client.destroy());modem.setTimeout(180000,()=>modem.destroy());
 const close=()=>{client.destroy();modem.destroy();};client.on('error',close);modem.on('error',close);client.on('close',()=>modem.destroy());modem.on('close',()=>client.destroy());client.pipe(modem);modem.pipe(client);
});
server.on('error',error=>{process.stderr.write(error.message+'\n');process.exitCode=1;});
server.listen(18081,'127.0.0.1');
