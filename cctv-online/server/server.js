import express from 'express';
import http from 'http';
import { Server } from 'socket.io';
import cors from 'cors';

const app = express();
app.use(cors());
app.use(express.json());
app.use(express.static(new URL('./public', import.meta.url).pathname));

const server = http.createServer(app);
const io = new Server(server, {
  cors: {
    origin: '*',
  },
});

let cameraSocketId = null;
const viewers = new Set();

io.on('connection', (socket) => {
  console.log('socket connected', socket.id);

  socket.on('register-camera', () => {
    cameraSocketId = socket.id;
    socket.join('camera');
    io.to(socket.id).emit('camera-registered');
    console.log('camera registered');
  });

  socket.on('register-viewer', () => {
    viewers.add(socket.id);
    socket.join('viewers');
    io.to(socket.id).emit('viewer-registered');
    console.log('viewer registered');
  });

  // WebRTC signaling
  socket.on('viewer-offer', (offer) => {
    if (!cameraSocketId) {
      io.to(socket.id).emit('no-camera');
      return;
    }
    io.to(cameraSocketId).emit('viewer-offer', { offer, viewerId: socket.id });
  });

  socket.on('camera-answer', ({ answer, viewerId }) => {
    if (viewerId) io.to(viewerId).emit('camera-answer', answer);
  });

  socket.on('ice-candidate', ({ target, candidate }) => {
    if (target) io.to(target).emit('ice-candidate', { candidate, from: socket.id });
  });

  // Control channel (ex: stop/start)
  socket.on('control', (payload) => {
    if (cameraSocketId) io.to(cameraSocketId).emit('control', payload);
  });

  socket.on('disconnect', () => {
    viewers.delete(socket.id);
    if (socket.id === cameraSocketId) {
      cameraSocketId = null;
      io.to('viewers').emit('camera-offline');
    }
    console.log('socket disconnected', socket.id);
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => {
  console.log(`Signaling server running on http://localhost:${PORT}`);
});
