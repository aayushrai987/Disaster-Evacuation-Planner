const fs = require('fs-extra');

const sourceDir = 'node_modules/leaflet/dist/images';
const destDir = 'public/leaflet-images';

fs.ensureDirSync(destDir);
fs.copySync(sourceDir, destDir, { overwrite: true }, (err) => {
  if (err) console.error(err);
  else console.log('Leaflet images copied to', destDir);
});

fs.copySync('node_modules/leaflet/dist/leaflet.css', 'public/leaflet/dist/leaflet.css');
fs.copySync('node_modules/leaflet-draw/dist/leaflet.draw.css', 'public/leaflet-draw/dist/leaflet.draw.css');
fs.copySync('node_modules/leaflet-draw/dist/images', 'public/leaflet-draw/dist/images');