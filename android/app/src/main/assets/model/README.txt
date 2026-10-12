Modelo de deteccion (no versionado)
===================================

Esta carpeta debe contener, para que la app pueda detectar pepas:

  model.json                      contrato del modelo (entrada, salida, clases, posprocesado)
  cuentapepas_nano_fp16.tflite    modelo en media precision (el que se prefiere)
  cuentapepas_nano_fp32.tflite    modelo en precision completa (respaldo)

Los nombres exactos de los .tflite los dicta model.json (campo "files").

Los tres archivos los produce el pipeline de ML con:

    cuentapepas export

(Plan A, Task A6; ver ml/). Copie el contenido de la carpeta de salida aqui.
Los .tflite estan ignorados por git a proposito (*.tflite en .gitignore).

La app NO trae un modelo de relleno: si falta model.json o el .tflite, la
pantalla en vivo muestra "Falta el modelo" y no se detecta nada. Nunca se
inventan detecciones.

Varias resoluciones de entrada (v0.2)
-------------------------------------

Si existe model/variants.json, la app usa una carpeta por resolucion:

  model/variants.json                       {"default": "<id>", "variants": [{"id", "label_es", "input": [alto, ancho]}]}
  model/<id>/model.json                     contrato de esa resolucion ("variant": "<id>", input.width/height, output.shape)
  model/<id>/cuentapepas_nano_fp16.tflite   (y _fp32.tflite)

Cada model.json debe coincidir con el tamano declarado en variants.json; si no, la app
se niega a cargarlo. Si variants.json no existe, se usa la disposicion plana de arriba
como una unica resolucion llamada "legacy".
