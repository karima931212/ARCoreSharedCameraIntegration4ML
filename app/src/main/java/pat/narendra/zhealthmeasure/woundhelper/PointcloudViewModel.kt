package pat.narendra.zhealthmeasure.woundhelper


import com.google.ar.core.PointCloud
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import kotlin.math.pow
import kotlin.math.sqrt


class PointCloud3d {
    //pointMap is <unique ID>, <location of Point> per acquire pointcloud in ARCore
    var pointMap = mutableMapOf<Int, Vector3>()
    var nearestDistance = 10E20f
    var angle = 0f //angle from the camera los to the vector to nearest point
    var maxPoints = 1000
    val idList = mutableListOf<Int>() //keeps track of the
    var cameraRotation = Quaternion(0f, 0f, 0f, 1f)
    var cameraPosition = Vector3(0f, 0f, 0f)
    var isStarted = false
    var nearestPointID = 0  //initialize

//starts the creation of point cloud from successive update calls
    fun start() {
        this.nearestDistance = 1E20f //set nearest point to near infinity
        this.pointMap.clear()
        this.idList.clear()
        this.isStarted = true
    }
//stops the updates when in a new frame update loop for example
    fun stop() {
        isStarted = false
    }
//updates the 3D persistent pointcloud with the current set of points
    fun update(pointCloud: PointCloud) {
        if (isStarted) {
            val noOfPoints = pointCloud.ids.limit()
            val pointArray = FloatArray(noOfPoints * 4)
            val idArray = IntArray(noOfPoints)
            //the point cloud is are two float buffers. First is a buffer size 4* no of points
            // organized as X, Y, Z coords and a conf measure, repeated at a
            //stride of four elements. The second buffer size no of points contains the
            // coresponding ids as one int per point.
            // first convert buffers to point arrays and then
            // Reformat the two arrays into ( )id, vector3) map
            //ignore the confidence number in each point.
            if (noOfPoints > 0) {
                pointCloud.points.get(pointArray)  //get the coordinates and conf number
                pointCloud.ids.get(idArray) //get unique id for each point
                pointCloud.close()
                idArray.forEachIndexed { i, id ->
                    val j = i * 4  // stride of 4 for each point
                    val point = Vector3(pointArray[j], pointArray[j + 1], pointArray[j + 2])
                    pointMap[id] = point
                    idList.add(id)
                    //limit the pointmap to reasonable size by removing oldest IDs in the cloud and update idList
                    if (pointMap.size > maxPoints) {
                        pointMap.remove(idList[0])
                        idList.removeAt(0)
                    }
                }
            }
        }
    }
//
    fun closestDistance2Camera(camera: Vector3):Float {
        nearestDistance = 10E20f
        if (!pointMap.isNullOrEmpty()) {
            pointMap.forEach { (id, point) ->
                val dist = distance(camera, point)?: 10E20f  //if null, use a very large no.
                if(dist < nearestDistance) nearestDistance = dist
                nearestPointID = id
            }
        }
        return nearestDistance
    }
}


fun distance(p1: Vector3?, p2: Vector3?): Float? = if ((p1 != null) && (p2 != null))
    sqrt((p1.x - p2.x).pow(2) + (p1.y - p2.y).pow(2) + (p1.z - p2.z).pow(2)) else null

fun main(){
    val pc = PointCloud3d()
    pc.start()
    val dist = pc.closestDistance2Camera(Vector3(0f,0f,0f))






}